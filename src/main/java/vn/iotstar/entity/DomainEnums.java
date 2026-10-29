package vn.iotstar.entity;

public final class DomainEnums {
    private DomainEnums() {
    }

    public enum Role {
        USER("Khách hàng"), VENDOR("Người bán"), MANAGER("Quản lý"), ADMIN("Quản trị viên"), SHIPPER("Nhân viên giao hàng");
        private final String label;
        Role(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum ShopStatus {
        PENDING("Chờ duyệt"), APPROVED("Đã duyệt"), REJECTED("Từ chối"), SUSPENDED("Tạm khóa");
        private final String label;
        ShopStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum ProductStatus {
        ACTIVE("Đang bán"), HIDDEN("Đã ẩn"), OUT_OF_STOCK("Hết hàng");
        private final String label;
        ProductStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum OrderStatus {
        NEW("Đơn hàng mới"), CONFIRMED("Đã xác nhận"), PREPARING("Đang chuẩn bị"),
        READY_FOR_PICKUP("Sẵn sàng lấy hàng"), PICKED_UP("Đã lấy hàng"), SHIPPING("Đang giao"),
        DELIVERED("Đã giao"), DELIVERY_FAILED("Giao thất bại"), CANCELLED("Đã hủy"), RETURN_REQUESTED("Yêu cầu trả hàng"),
        RETURNED_REFUNDED("Đã hoàn tiền");
        private final String label;
        OrderStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
        public static java.util.List<OrderStatus> workQueueStatuses() {
            return java.util.List.of(NEW, CONFIRMED, PREPARING, READY_FOR_PICKUP,
                DELIVERY_FAILED, RETURN_REQUESTED);
        }
    }

    public enum ShipperAssignmentStatus {
        PENDING, ACCEPTED, REJECTED, EXPIRED, CANCELLED
    }

    public enum PaymentMethod {
        COD("Thanh toán khi nhận hàng"), VNPAY("VNPay"), MOMO("MoMo"),
        BANK_TRANSFER("Chuyển khoản QR");
        private final String label;
        PaymentMethod(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum PaymentStatus {
        UNPAID("Chưa thanh toán"), PAID("Đã thanh toán"),
        REFUNDED("Đã hoàn tiền");
        private final String label;
        PaymentStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum PaymentTransactionStatus {
        PENDING("Chờ thanh toán"), AWAITING_REVIEW("Chờ Admin xác nhận"),
        SUCCESS("Thành công"), FAILED("Thất bại"), CANCELLED("Đã hủy"),
        EXPIRED("Đã hết hạn");
        private final String label;
        PaymentTransactionStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum LoyaltyPointType {
        EARN("Tích điểm từ đơn đã giao"), REFUND_REVERSAL("Thu hồi điểm do hoàn tiền");
        private final String label;
        LoyaltyPointType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum AuditAction {
        USER_LOCKED("Khóa tài khoản"), USER_UNLOCKED("Mở khóa tài khoản"),
        USER_ROLE_CHANGED("Đổi vai trò"), MANAGER_SHOPS_ASSIGNED("Phân công Manager"),
        SHOP_APPROVED("Duyệt cửa hàng"), SHOP_STATUS_CHANGED("Đổi trạng thái cửa hàng"),
        SHOP_COMMISSION_CHANGED("Đổi chiết khấu ứng dụng"), SHIPPER_ASSIGNED("Phân công Shipper"),
        ORDER_STATUS_CHANGED("Đổi trạng thái đơn"), SHIPPER_ORDER_STATUS("Shipper cập nhật giao hàng"),
        BANK_TRANSFER_CONFIRMED("Xác nhận chuyển khoản");
        private final String label;
        AuditAction(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum InventoryTransactionType {
        INITIAL("Nhập kho ban đầu"), RESTOCK("Nhập thêm"), ADJUSTMENT("Điều chỉnh"),
        SALE("Bán hàng"), CANCELLATION("Hủy đơn"), RETURN_REFUND("Trả hàng - hoàn tiền");
        private final String label;
        InventoryTransactionType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum SugarLevel {
        PERCENT_30("30% đường"), PERCENT_50("50% đường"), PERCENT_70("70% đường"), PERCENT_100("100% đường");
        private final String label;
        SugarLevel(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum IceLevel {
        NO_ICE("Không đá"), LESS_ICE("Ít đá"), NORMAL("Đá bình thường");
        private final String label;
        IceLevel(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum VoucherType {
        PERCENT("Giảm theo phần trăm"), FIXED("Giảm số tiền cố định"), SHIPPING("Giảm phí vận chuyển");
        private final String label;
        VoucherType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum VoucherUsageStatus {
        USED("Đã sử dụng"), CANCELLED("Đã hoàn do hủy đơn"), REFUNDED("Đã hoàn do trả hàng");
        private final String label;
        VoucherUsageStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum PromotionType {
        PRODUCT_PERCENT("Giảm giá sản phẩm (%)"), SHIPPING_DISCOUNT("Giảm phí vận chuyển");
        private final String label;
        PromotionType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum OtpPurpose { REGISTER, RESET_PASSWORD }
    public enum MediaType { IMAGE, VIDEO }
    public enum ReturnReason {
        WRONG_ITEM("Giao sai sản phẩm"), DAMAGED("Sản phẩm hư hỏng/đổ vỡ"),
        QUALITY("Chất lượng không đúng mô tả"), MISSING_ITEM("Thiếu sản phẩm"), OTHER("Lý do khác");
        private final String label;
        ReturnReason(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum ReturnStatus {
        REQUESTED("Chờ shop xử lý"), APPROVED("Đã chấp nhận"), REJECTED("Đã từ chối"),
        RETURNING("Khách đang gửi trả"), RECEIVED("Shop đã nhận hàng"),
        REFUND_PENDING("Chờ hoàn tiền"), REFUNDED("Đã hoàn tiền");
        private final String label;
        ReturnStatus(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum RefundVerification {
        PENDING("Chưa xác nhận hoàn tiền"),
        MANUAL_CONFIRMED("Đã xác nhận hoàn tiền thủ công"),
        GATEWAY_CONFIRMED("Cổng thanh toán đã xác nhận hoàn tiền"),
        LEGACY_UNKNOWN("Đơn cũ chưa có thông tin xác nhận hoàn tiền");
        private final String label;
        RefundVerification(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
