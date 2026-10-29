package vn.iotstar.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.DomainEnums.ReturnReason;

public final class WebForms {
    private WebForms() {
    }

    @Getter @Setter
    public static class RegisterForm {
        @NotBlank @Size(min = 4, max = 60)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch ngang và gạch dưới")
        private String username;
        @NotBlank @Email @Size(max = 160)
        private String email;
        @NotBlank @Size(min = 2, max = 120)
        private String fullName;
        @NotBlank @Size(min = 8, max = 72)
        private String password;
        @NotBlank
        private String confirmPassword;
    }

    @Getter @Setter
    public static class ResetPasswordForm {
        @NotBlank @Email private String email;
        @NotBlank @Size(min = 8, max = 72) private String password;
        @NotBlank private String confirmPassword;
    }

    @Getter @Setter
    public static class ProfileForm {
        @NotBlank(message = "Vui lòng nhập họ và tên")
        @Size(min = 2, max = 120, message = "Họ và tên phải có từ 2 đến 120 ký tự")
        private String fullName;
        @Pattern(regexp = "^$|^[0-9+ -]{9,20}$", message = "Số điện thoại phải có từ 9 đến 20 ký tự và chỉ gồm số, dấu +, - hoặc khoảng trắng")
        private String phone;
    }

    @Getter @Setter
    public static class AddressForm {
        @NotBlank(message = "Vui lòng nhập tên người nhận")
        @Size(max = 120, message = "Tên người nhận không được quá 120 ký tự") private String receiverName;
        @NotBlank(message = "Vui lòng nhập số điện thoại")
        @Pattern(regexp = "^$|^[0-9+ -]{9,20}$", message = "Số điện thoại phải có từ 9 đến 20 ký tự") private String phone;
        @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
        @Size(max = 120, message = "Tỉnh hoặc thành phố không được quá 120 ký tự") private String province;
        @NotBlank(message = "Vui lòng nhập quận hoặc huyện")
        @Size(max = 120, message = "Quận hoặc huyện không được quá 120 ký tự") private String district;
        @NotBlank(message = "Vui lòng nhập phường hoặc xã")
        @Size(max = 120, message = "Phường hoặc xã không được quá 120 ký tự") private String ward;
        @NotBlank(message = "Vui lòng nhập số nhà và tên đường")
        @Size(max = 255, message = "Địa chỉ cụ thể không được quá 255 ký tự") private String detail;
        private boolean defaultAddress;
    }

    @Getter @Setter
    public static class BankForm {
        @Size(max = 120, message = "Tên ngân hàng không được quá 120 ký tự")
        private String bankName;
        @Size(max = 120, message = "Tên chủ tài khoản không được quá 120 ký tự")
        private String accountName;
        @Pattern(regexp = "^$|^[0-9]{6,24}$", message = "Số tài khoản phải gồm từ 6 đến 24 chữ số")
        private String accountNumber;
    }

    @Getter @Setter
    public static class ChangePasswordForm {
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại")
        private String currentPassword;
        @NotBlank(message = "Vui lòng nhập mật khẩu mới")
        @Size(min = 8, max = 72, message = "Mật khẩu mới phải có từ 8 đến 72 ký tự")
        private String newPassword;
        @NotBlank(message = "Vui lòng xác nhận mật khẩu mới")
        private String confirmPassword;
    }

    @Getter @Setter
    public static class ShopForm {
        @NotBlank(message = "Vui lòng nhập tên cửa hàng")
        @Size(min = 3, max = 150, message = "Tên cửa hàng phải có từ 3 đến 150 ký tự") private String name;
        @NotBlank(message = "Vui lòng nhập mô tả cửa hàng")
        @Size(min = 20, max = 1000, message = "Mô tả phải có từ 20 đến 1.000 ký tự") private String description;
        @NotBlank(message = "Vui lòng nhập số điện thoại")
        @Pattern(regexp = "^[0-9+() .-]{9,20}$", message = "Số điện thoại không hợp lệ") private String phone;
        @NotBlank(message = "Vui lòng nhập email cửa hàng")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 160, message = "Email không được vượt quá 160 ký tự") private String email;
        @NotBlank(message = "Vui lòng nhập số nhà và tên đường")
        @Size(max = 255, message = "Địa chỉ không được vượt quá 255 ký tự") private String address;
        @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
        @Size(max = 120, message = "Tỉnh/thành phố không được vượt quá 120 ký tự") private String province;
        @NotBlank(message = "Vui lòng nhập quận hoặc huyện")
        @Size(max = 120, message = "Quận/huyện không được vượt quá 120 ký tự") private String district;
        @NotBlank(message = "Vui lòng nhập phường hoặc xã")
        @Size(max = 120, message = "Phường/xã không được vượt quá 120 ký tự") private String ward;
        @DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
        @DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90") private BigDecimal latitude;
        @DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
        @DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180") private BigDecimal longitude;
        @NotNull(message = "Vui lòng chọn giờ mở cửa") @DateTimeFormat(pattern = "HH:mm") private LocalTime openingTime;
        @NotNull(message = "Vui lòng chọn giờ đóng cửa") @DateTimeFormat(pattern = "HH:mm") private LocalTime closingTime;
    }

    @Getter @Setter
    public static class ProductForm {
        private Long version;
        @NotBlank @Size(min = 3, max = 180) private String name;
        @NotBlank @Size(min = 2, max = 60) private String sku;
        @NotNull private Long categoryId;
        @NotBlank @Size(min = 20, max = 5000) private String description;
        @NotNull @DecimalMin("1000") private BigDecimal price;
        @Min(0) private int stock;
        @Size(max = 255) private String stockNote;
        @NotNull private ProductStatus status = ProductStatus.ACTIVE;
        private boolean sizeMActive = true;
        @NotNull(message = "Vui lòng nhập phụ thu size M")
        @DecimalMin(value = "0", message = "Phụ thu size M không được âm")
        private BigDecimal sizeMExtraPrice = BigDecimal.ZERO;
        private boolean sizeLActive = true;
        @NotNull(message = "Vui lòng nhập phụ thu size L")
        @DecimalMin(value = "0", message = "Phụ thu size L không được âm")
        private BigDecimal sizeLExtraPrice = new BigDecimal("7000");
        private boolean sizeXlActive = true;
        @NotNull(message = "Vui lòng nhập phụ thu size XL")
        @DecimalMin(value = "0", message = "Phụ thu size XL không được âm")
        private BigDecimal sizeXlExtraPrice = new BigDecimal("12000");
        private List<Long> toppingIds = new ArrayList<>();
    }

    @Getter @Setter
    public static class PromotionForm {
        @NotBlank @Size(min = 3, max = 180) private String name;
        @NotNull private PromotionType type;
        @NotNull @DecimalMin("0.01") private BigDecimal discountValue;
        private Long productId;
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") private LocalDateTime startsAt;
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") private LocalDateTime endsAt;
        private boolean active = true;
    }

    @Getter @Setter
    public static class VoucherForm {
        @NotBlank(message = "Vui lòng nhập mã giảm giá")
        @Size(min = 3, max = 40, message = "Mã giảm giá phải có từ 3 đến 40 ký tự")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Mã chỉ được gồm chữ, số, dấu gạch ngang hoặc gạch dưới")
        private String code;
        @NotBlank(message = "Vui lòng nhập tên chương trình")
        @Size(max = 180, message = "Tên chương trình không được quá 180 ký tự")
        private String name;
        @NotNull(message = "Vui lòng chọn loại giảm giá")
        private VoucherType type = VoucherType.PERCENT;
        @NotNull(message = "Vui lòng nhập giá trị giảm")
        @DecimalMin(value = "0.01", message = "Giá trị giảm phải lớn hơn 0")
        private BigDecimal discountValue;
        @NotNull(message = "Vui lòng nhập giá trị đơn tối thiểu")
        @DecimalMin(value = "0", message = "Giá trị đơn tối thiểu không được âm")
        private BigDecimal minimumOrder = BigDecimal.ZERO;
        @DecimalMin(value = "0.01", message = "Mức giảm tối đa phải lớn hơn 0")
        private BigDecimal maxDiscount;
        @Min(value = 0, message = "Số lượng không được âm")
        private int quantity;
        @Min(value = 1, message = "Mỗi người phải được dùng ít nhất 1 lượt")
        private int maxUsesPerUser = 1;
        @NotNull(message = "Vui lòng chọn thời gian bắt đầu")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime startsAt;
        @NotNull(message = "Vui lòng chọn thời gian kết thúc")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime endsAt;
        private boolean active = true;
    }

    @Getter @Setter
    public static class CategoryForm {
        @NotBlank @Size(min = 2, max = 120) private String name;
        @Size(max = 500) private String description;
        private boolean active = true;
    }

    @Getter @Setter
    public static class CarrierForm {
        @NotBlank @Size(min = 2, max = 150) private String name;
        @NotNull @DecimalMin("0") private BigDecimal shippingFee;
        private boolean active = true;
    }

    @Getter @Setter
    public static class ToppingForm {
        @NotBlank(message = "Vui lòng nhập tên topping")
        @Size(min = 2, max = 120, message = "Tên topping phải từ 2 đến 120 ký tự")
        private String name;
        @NotNull(message = "Vui lòng nhập giá topping")
        @DecimalMin(value = "0", message = "Giá topping không được âm")
        private BigDecimal price = BigDecimal.ZERO;
        private boolean active = true;
    }

    @Getter @Setter
    public static class CheckoutForm {
        @NotNull private Long addressId;
        @NotNull private Long carrierId;
        @NotEmpty(message = "Vui lòng chọn ít nhất một sản phẩm để thanh toán")
        private List<Long> itemIds = new ArrayList<>();
        @Size(max = 40) private String voucherCode;
        @Size(max = 500) private String note;
        @NotNull(message = "Vui lòng chọn phương thức thanh toán")
        private vn.iotstar.entity.DomainEnums.PaymentMethod paymentMethod =
            vn.iotstar.entity.DomainEnums.PaymentMethod.COD;
    }

    @Getter @Setter
    public static class ReviewForm {
        @Min(1) @Max(5) private int rating = 5;
        @NotBlank @Size(min = 50, max = 3000) private String content;
    }

    @Getter @Setter
    public static class ReturnRequestForm {
        @NotBlank(message = "Vui lòng chọn tài khoản ngân hàng nhận tiền hoàn")
        private String refundBankAccount;
        @NotNull(message = "Vui lòng chọn lý do trả hàng")
        private ReturnReason reason;
        @NotBlank(message = "Vui lòng mô tả vấn đề")
        @Size(min = 20, max = 2000, message = "Mô tả phải từ 20 đến 2000 ký tự")
        private String description;
        @NotNull(message = "Vui lòng nhập số tiền đề nghị hoàn")
        @DecimalMin(value = "0.01", message = "Số tiền hoàn phải lớn hơn 0")
        private BigDecimal refundAmount;
    }
}
