package vn.iotstar.entity;

public final class DomainEnums {
    private DomainEnums() {
    }
    public enum ProductStatus {
        ACTIVE("Đang bán"),
        HIDDEN("Đã ẩn"),
        OUT_OF_STOCK("Hết hàng");

        private final String label;

        ProductStatus(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
    public enum Role {
        USER("Khách hàng"), VENDOR("Người bán"), MANAGER("Quản lý"),
        ADMIN("Quản trị viên"), SHIPPER("Nhân viên giao hàng");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum ShopStatus {
        PENDING,
        APPROVED,
        REJECTED,
        SUSPENDED
    }
    public enum SugarLevel {
        PERCENT_30("30% đường"),
        PERCENT_50("50% đường"),
        PERCENT_70("70% đường"),
        PERCENT_100("100% đường");

        private final String label;

        SugarLevel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum IceLevel {
        NO_ICE("Không đá"),
        LESS_ICE("Ít đá"),
        NORMAL("Đá bình thường");

        private final String label;

        IceLevel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
    public enum OtpPurpose {
        REGISTER,
        RESET_PASSWORD
    }
}