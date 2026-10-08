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

    public enum OtpPurpose { REGISTER }
}
