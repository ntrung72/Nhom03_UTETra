package vn.iotstar.service;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.config.MomoProperties;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

/** MoMo Sandbox uses signed server IPNs; browser redirects never settle orders. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class MomoService {
    private final MomoProperties properties;
    private final MomoGatewayClient gateway;
    private final PaymentTransactionRepository transactions;
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public boolean isConfigured() { return properties.isConfigured(); }
    public void requireConfigured() {
        if (!isConfigured()) throw new IllegalArgumentException("MoMo Sandbox chưa được cấu hình. Vui lòng chọn phương thức khác.");
    }
    @Transactional
    public String createPaymentUrl(User buyer, List<CustomerOrder> created, String ip) {
        requireConfigured();
        if (created == null || created.isEmpty()) throw new IllegalArgumentException("Không có đơn hàng để thanh toán.");
        return paymentUrl(lockCheckout(created.getFirst().getCheckoutReference(),buyer.getId()),ip);
    }
    @Transactional
    public String retryPaymentUrl(User buyer, Long id, String ip) {
        requireConfigured();
        var owned=orders.findOwnedPaymentSummary(id,buyer.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return paymentUrl(lockCheckout(owned.getCheckoutReference(),buyer.getId()),ip);
    }
    private String paymentUrl(List<CustomerOrder> group, String ip) {
        if (group.isEmpty() || group.stream().anyMatch(o -> o.getPaymentMethod()!=PaymentMethod.MOMO || o.getStatus()!=OrderStatus.NEW
            || o.getPaymentStatus()!=PaymentStatus.UNPAID || o.getPaymentExpiresAt()==null || !o.getPaymentExpiresAt().isAfter(now())))
            throw new IllegalArgumentException("Chỉ đơn MoMo mới, chưa thanh toán và còn hạn mới được thanh toán.");
        String checkout=group.getFirst().getCheckoutReference();
        var previous=transactions.findFirstByCheckoutReferenceOrderByIdDesc(checkout).orElse(null);
        if (transactions.findForCheckout(checkout).stream().anyMatch(PaymentTransaction::isReconciliationRequired))
            throw new IllegalArgumentException("Giao dịch đang cần đối soát. Vui lòng liên hệ cửa hàng.");
        if(previous!=null && previous.getStatus()==PaymentTransactionStatus.PENDING && previous.getPaymentUrl()!=null)
            return previous.getPaymentUrl();
        var total=group.stream().map(CustomerOrder::getTotalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        long amount;
        try { amount=total.longValueExact(); if(amount<=0)throw new ArithmeticException(); }
        catch(ArithmeticException ex){throw new IllegalArgumentException("Số tiền thanh toán MoMo không hợp lệ.");}
        String reference="MO"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        String info="Thanh toan don hang UTETra "+reference;
        String raw="accessKey="+properties.getAccessKey().trim()+"&amount="+amount+"&extraData=&ipnUrl="+properties.getIpnUrl()
            +"&orderId="+reference+"&orderInfo="+info+"&partnerCode="+properties.getPartnerCode().trim()
            +"&redirectUrl="+properties.getRedirectUrl()+"&requestId="+reference+"&requestType=captureWallet";
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("partnerCode",properties.getPartnerCode().trim());body.put("requestId",reference);body.put("orderId",reference);
        body.put("amount",amount);body.put("orderInfo",info);body.put("redirectUrl",properties.getRedirectUrl());body.put("ipnUrl",properties.getIpnUrl());
        body.put("requestType","captureWallet");body.put("extraData","");body.put("lang","vi");body.put("autoCapture",true);body.put("signature",sign(raw));
        var response=gateway.create(properties.getEndpoint(),body);
        if(!"0".equals(value(response,"resultCode")) || !reference.equals(value(response,"orderId"))
            || !reference.equals(value(response,"requestId")))throw new IllegalArgumentException("MoMo chưa tạo được yêu cầu thanh toán hợp lệ.");
        if(response.containsKey("amount")&&!Long.toString(amount).equals(value(response,"amount")))throw new IllegalArgumentException("Số tiền phản hồi MoMo không khớp.");
        if(response.containsKey("partnerCode")&&!properties.getPartnerCode().trim().equals(value(response,"partnerCode")))throw new IllegalArgumentException("Merchant MoMo không khớp.");
        String url=value(response,"payUrl");URI uri;
        try{uri=URI.create(url);}catch(RuntimeException ex){throw new IllegalArgumentException("Đường dẫn thanh toán không hợp lệ.");}
        if(!"https".equalsIgnoreCase(uri.getScheme())||!"test-payment.momo.vn".equalsIgnoreCase(uri.getHost())||url.length()>2000)
            throw new IllegalArgumentException("Đường dẫn thanh toán MoMo Sandbox không hợp lệ.");
        var deadline=group.stream().map(CustomerOrder::getPaymentExpiresAt).min(LocalDateTime::compareTo).orElseThrow();
        for(var order:group){
            var row=new PaymentTransaction();row.setOrder(order);row.setCheckoutReference(checkout);row.setTransactionCode(reference);
            row.setAmount(order.getTotalAmount());row.setExpiresAt(deadline);row.setCreatedAt(now());row.setClientIp(validIp(ip));row.setPaymentUrl(url);transactions.save(row);
        }
        transactions.flush();return url;
    }
    @Transactional
    public CallbackResult processCallback(Map<String,String> callback) {
        if(!hasValidSignature(callback)||!properties.getPartnerCode().trim().equals(callback.get("partnerCode")))return result(false,false);
        String reference=callback.get("orderId"),code=callback.get("resultCode");
        if(reference==null||!reference.equals(callback.get("requestId"))||!"momo_wallet".equals(callback.get("orderType"))
            ||!("Thanh toan don hang UTETra "+reference).equals(callback.get("orderInfo"))||code==null||!code.matches("[0-9]{1,8}"))return result(false,false);
        var candidate=transactions.findFirstByTransactionCodeOrderByIdAsc(reference).orElse(null);
        if(candidate==null||candidate.getOrder().getPaymentMethod()!=PaymentMethod.MOMO)return result(false,false);
        var group=lockCheckout(candidate.getCheckoutReference(),candidate.getOrder().getUser().getId());
        var rows=transactions.findByCodeForUpdate(reference);rows.forEach(t->entityManager.refresh(t,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        if(rows.size()!=group.size()||!new HashSet<>(rows.stream().map(t->t.getOrder().getId()).toList()).equals(new HashSet<>(group.stream().map(CustomerOrder::getId).toList())))return result(false,false);
        try{if(rows.stream().map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal(callback.get("amount")))!=0)return result(false,false);}
        catch(RuntimeException ex){return result(false,false);}
        boolean paid="0".equals(code);
        if(rows.stream().anyMatch(t->t.getStatus()!=PaymentTransactionStatus.PENDING)){
            boolean settled=rows.stream().allMatch(t->t.getStatus()==PaymentTransactionStatus.SUCCESS);
            if(paid&&!settled)rows.forEach(t->t.setReconciliationRequired(true));
            return result(true,settled);
        }
        if(group.stream().anyMatch(o->o.getPaymentMethod()!=PaymentMethod.MOMO||o.getStatus()!=OrderStatus.NEW||o.getPaymentStatus()!=PaymentStatus.UNPAID
            ||o.getPaymentExpiresAt()==null||!o.getPaymentExpiresAt().isAfter(now()))){
            if(paid)rows.forEach(t->t.setReconciliationRequired(true));return result(true,false);
        }
        // 9000 is authorized, not captured. Pending/authorized results do not mark PAID.
        if(Set.of("1000","7000","7002","9000").contains(code))return result(true,false);
        String provider=callback.get("transId");
        if(paid&&(provider==null||!provider.matches("[0-9]{1,30}")))return result(false,false);
        for(var row:rows){row.setCompletedAt(now());row.setProviderTransactionNo(provider!=null&&provider.matches("[0-9]{1,30}")?provider:null);
            row.setStatus(paid?PaymentTransactionStatus.SUCCESS:PaymentTransactionStatus.FAILED);
            if(paid){row.setPaidAt(now());row.getOrder().setPaymentStatus(PaymentStatus.PAID);}}
        return result(true,paid);
    }
    @Transactional
    public void cancelCheckout(User buyer,Long id){
        var owned=orders.findOwnedPaymentSummary(id,buyer.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        var group=lockCheckout(owned.getCheckoutReference(),buyer.getId());
        if(group.stream().anyMatch(o->o.getPaymentMethod()!=PaymentMethod.MOMO||o.getStatus()!=OrderStatus.NEW||o.getPaymentStatus()!=PaymentStatus.UNPAID))
            throw new IllegalArgumentException("Chỉ có thể hủy đơn MoMo mới chưa thanh toán.");
        cancel(group,PaymentTransactionStatus.CANCELLED);
    }
    @Transactional
    public int expireCheckout(String reference){
        var candidate=transactions.findFirstByCheckoutReferenceOrderByIdDesc(reference).orElse(null);
        if(candidate==null||candidate.getOrder().getPaymentMethod()!=PaymentMethod.MOMO)return 0;
        var group=lockCheckout(reference,candidate.getOrder().getUser().getId());
        if(group.stream().anyMatch(o->o.getPaymentMethod()!=PaymentMethod.MOMO||o.getPaymentStatus()!=PaymentStatus.UNPAID
            ||(o.getStatus()!=OrderStatus.NEW&&o.getStatus()!=OrderStatus.CANCELLED)||o.getPaymentExpiresAt()==null||o.getPaymentExpiresAt().isAfter(now())))return 0;
        if(group.stream().noneMatch(o->o.getStatus()==OrderStatus.NEW))return 0;
        return cancel(group,PaymentTransactionStatus.EXPIRED);
    }
    private int cancel(List<CustomerOrder> group,PaymentTransactionStatus status){
        group.stream().flatMap(o->o.getItems().stream()).map(OrderItem::getProduct).distinct().sorted(Comparator.comparing(Product::getId))
            .forEach(p->entityManager.refresh(p,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        for(var order:group)if(order.getStatus()==OrderStatus.NEW)orderService.cancelByUser(order.getUser(),order.getId());
        int count=0;for(var row:transactions.findForCheckout(group.getFirst().getCheckoutReference()))if(row.getStatus()==PaymentTransactionStatus.PENDING){row.setStatus(status);row.setCompletedAt(now());count++;}return count;
    }
    private List<CustomerOrder> lockCheckout(String reference,Long owner){
        if(reference==null||reference.isBlank())throw new IllegalArgumentException("Không tìm thấy lần đặt hàng.");
        users.findByIdForUpdate(owner).orElseThrow();var group=orders.findByCheckoutReferenceOrderByIdAsc(reference);
        if(group.isEmpty()||group.stream().anyMatch(o->!o.getUser().getId().equals(owner)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        group.forEach(o->entityManager.refresh(o,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));return group;
    }
    public boolean hasValidSignature(Map<String,String> callback){
        if(!isConfigured()||callback==null)return false;String supplied=callback.get("signature");if(supplied==null||!supplied.matches("[A-Fa-f0-9]{64}"))return false;
        String raw="accessKey="+properties.getAccessKey().trim();
        for(String key:List.of("amount","extraData","message","orderId","orderInfo","orderType","partnerCode","payType","requestId","responseTime","resultCode","transId"))raw+="&"+key+"="+value(callback,key);
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(raw)),HexFormat.of().parseHex(supplied));
    }
    private String sign(String raw){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(properties.getSecretKey().trim().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("Không thể ký yêu cầu MoMo.",ex);}}
    private String value(Map<String,?> map,String key){Object value=map.get(key);return value==null?"":value.toString();}
    private String validIp(String ip){return ip!=null&&ip.matches("[0-9A-Fa-f:.]{3,45}")?ip:"127.0.0.1";}
    private LocalDateTime now(){return LocalDateTime.now(ZONE);}
    private CallbackResult result(boolean valid,boolean success){return new CallbackResult(valid,success);}
    public record CallbackResult(boolean valid,boolean success){}
}
