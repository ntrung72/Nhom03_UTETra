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

public final class WebForms {

    private WebForms() {
    }

    @Getter
    @Setter
    public static class RegisterForm {

        @NotBlank
        @Size(min = 4, max = 60)
        @Pattern(
            regexp = "^[a-zA-Z0-9._-]+$",
            message = "Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch ngang và gạch dưới"
        )
        private String username;

        @NotBlank
        @Email
        @Size(max = 160)
        private String email;

        @NotBlank
        @Size(min = 2, max = 120)
        private String fullName;

        @NotBlank
        @Size(min = 8, max = 72)
        private String password;

        @NotBlank
        private String confirmPassword;
    }

    @Getter
    @Setter
    public static class ProfileForm {

        @NotBlank(message = "Vui lòng nhập họ và tên")
        @Size(
            min = 2,
            max = 120,
            message = "Họ và tên phải có từ 2 đến 120 ký tự"
        )
        private String fullName;

        @Pattern(
            regexp = "^$|^[0-9+ -]{9,20}$",
            message = "Số điện thoại phải có từ 9 đến 20 ký tự và chỉ gồm số, dấu +, - hoặc khoảng trắng"
        )
        private String phone;
    }

    @Getter
    @Setter
    public static class AddressForm {

        @NotBlank(message = "Vui lòng nhập tên người nhận")
        @Size(
            max = 120,
            message = "Tên người nhận không được quá 120 ký tự"
        )
        private String receiverName;

        @NotBlank(message = "Vui lòng nhập số điện thoại")
        @Pattern(
            regexp = "^$|^[0-9+ -]{9,20}$",
            message = "Số điện thoại phải có từ 9 đến 20 ký tự"
        )
        private String phone;

        @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
        @Size(
            max = 120,
            message = "Tỉnh hoặc thành phố không được quá 120 ký tự"
        )
        private String province;

        @NotBlank(message = "Vui lòng nhập quận hoặc huyện")
        @Size(
            max = 120,
            message = "Quận hoặc huyện không được quá 120 ký tự"
        )
        private String district;

        @NotBlank(message = "Vui lòng nhập phường hoặc xã")
        @Size(
            max = 120,
            message = "Phường hoặc xã không được quá 120 ký tự"
        )
        private String ward;

        @NotBlank(message = "Vui lòng nhập số nhà và tên đường")
        @Size(
            max = 255,
            message = "Địa chỉ cụ thể không được quá 255 ký tự"
        )
        private String detail;

        private boolean defaultAddress;
    }

    @Getter
    @Setter
    public static class BankForm {

        @Size(
            max = 120,
            message = "Tên ngân hàng không được quá 120 ký tự"
        )
        private String bankName;

        @Size(
            max = 120,
            message = "Tên chủ tài khoản không được quá 120 ký tự"
        )
        private String accountName;

        @Pattern(
            regexp = "^$|^[0-9]{6,24}$",
            message = "Số tài khoản phải gồm từ 6 đến 24 chữ số"
        )
        private String accountNumber;
    }

    @Getter
    @Setter
    public static class ChangePasswordForm {

        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại")
        @Size(max = 72)
        private String currentPassword;

        @NotBlank(message = "Vui lòng nhập mật khẩu mới")
        @Size(
            min = 8,
            max = 72,
            message = "Mật khẩu mới phải có từ 8 đến 72 ký tự"
        )
        private String newPassword;

        @NotBlank(message = "Vui lòng xác nhận mật khẩu mới")
        @Size(max = 72)
        private String confirmPassword;
    }

    @Getter
    @Setter
    public static class ShopForm {

        @NotBlank(message = "Vui lòng nhập tên cửa hàng")
        @Size(
            min = 3,
            max = 150,
            message = "Tên cửa hàng phải có từ 3 đến 150 ký tự"
        )
        private String name;

        @NotBlank(message = "Vui lòng nhập mô tả cửa hàng")
        @Size(
            min = 20,
            max = 1000,
            message = "Mô tả phải có từ 20 đến 1.000 ký tự"
        )
        private String description;

        @NotBlank(message = "Vui lòng nhập số điện thoại")
        @Pattern(
            regexp = "^[0-9+() .-]{9,20}$",
            message = "Số điện thoại không hợp lệ"
        )
        private String phone;

        @NotBlank(message = "Vui lòng nhập email cửa hàng")
        @Email(message = "Email không đúng định dạng")
        @Size(
            max = 160,
            message = "Email không được vượt quá 160 ký tự"
        )
        private String email;

        @NotBlank(message = "Vui lòng nhập số nhà và tên đường")
        @Size(
            max = 255,
            message = "Địa chỉ không được vượt quá 255 ký tự"
        )
        private String address;

        @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
        @Size(
            max = 120,
            message = "Tỉnh/thành phố không được vượt quá 120 ký tự"
        )
        private String province;

        @NotBlank(message = "Vui lòng nhập quận hoặc huyện")
        @Size(
            max = 120,
            message = "Quận/huyện không được vượt quá 120 ký tự"
        )
        private String district;

        @NotBlank(message = "Vui lòng nhập phường hoặc xã")
        @Size(
            max = 120,
            message = "Phường/xã không được vượt quá 120 ký tự"
        )
        private String ward;

        @DecimalMin(
            value = "-90.0",
            message = "Vĩ độ phải từ -90 đến 90"
        )
        @DecimalMax(
            value = "90.0",
            message = "Vĩ độ phải từ -90 đến 90"
        )
        @Digits(
            integer = 3,
            fraction = 7,
            message = "Vĩ độ chỉ được có tối đa 7 chữ số thập phân"
        )
        private BigDecimal latitude;

        @DecimalMin(
            value = "-180.0",
            message = "Kinh độ phải từ -180 đến 180"
        )
        @DecimalMax(
            value = "180.0",
            message = "Kinh độ phải từ -180 đến 180"
        )
        @Digits(
            integer = 3,
            fraction = 7,
            message = "Kinh độ chỉ được có tối đa 7 chữ số thập phân"
        )
        private BigDecimal longitude;

        @NotNull(message = "Vui lòng chọn giờ mở cửa")
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime openingTime;

        @NotNull(message = "Vui lòng chọn giờ đóng cửa")
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime closingTime;
    }

    @Getter
    @Setter
    public static class CategoryForm {

        @NotBlank
        @Size(min = 2, max = 120)
        private String name;

        @Size(max = 500)
        private String description;

        private boolean active = true;
    }

    @Getter
    @Setter
    public static class ToppingForm {

        @NotBlank(message = "Vui lòng nhập tên topping")
        @Size(
            min = 2,
            max = 120,
            message = "Tên topping phải từ 2 đến 120 ký tự"
        )
        private String name;

        @NotNull(message = "Vui lòng nhập giá topping")
        @DecimalMin(
            value = "0",
            message = "Giá topping không được âm"
        )
        private BigDecimal price = BigDecimal.ZERO;

        private boolean active = true;
    }

    @Getter
    @Setter
    public static class ProductForm {

        @NotNull(message = "Thiếu phiên bản sản phẩm")
        @Min(
            value = 0,
            message = "Phiên bản sản phẩm không hợp lệ"
        )
        private Long version = 0L;

        @NotBlank(message = "Vui lòng nhập tên sản phẩm")
        @Size(
            min = 3,
            max = 180,
            message = "Tên sản phẩm phải có từ 3 đến 180 ký tự"
        )
        private String name;

        @NotBlank(message = "Vui lòng nhập mã SKU")
        @Size(
            min = 2,
            max = 60,
            message = "SKU phải có từ 2 đến 60 ký tự"
        )
        @Pattern(
            regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$",
            message = "SKU phải bắt đầu bằng chữ hoặc số và chỉ gồm chữ, số, dấu chấm, gạch ngang, gạch dưới"
        )
        private String sku;

        @NotNull(message = "Vui lòng chọn danh mục")
        @Positive(message = "Danh mục không hợp lệ")
        private Long categoryId;

        @NotBlank(message = "Vui lòng nhập mô tả sản phẩm")
        @Size(
            min = 20,
            max = 5000,
            message = "Mô tả phải có từ 20 đến 5.000 ký tự"
        )
        private String description;

        @NotNull(message = "Vui lòng nhập giá sản phẩm")
        @DecimalMin(
            value = "1000",
            message = "Giá sản phẩm phải từ 1.000 đồng"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Giá chỉ được có tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal price;

        @NotNull(message = "Vui lòng nhập số lượng tồn kho")
        @Min(value = 0, message = "Tồn kho không được âm")
        private Integer stock = 0;

        @NotNull(message = "Vui lòng chọn trạng thái sản phẩm")
        private ProductStatus status = ProductStatus.ACTIVE;

        private boolean sizeMActive = true;

        @NotNull(message = "Vui lòng nhập phụ thu size M")
        @DecimalMin(
            value = "0",
            message = "Phụ thu size M không được âm"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Phụ thu size M tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal sizeMExtraPrice = BigDecimal.ZERO;

        private boolean sizeLActive = true;

        @NotNull(message = "Vui lòng nhập phụ thu size L")
        @DecimalMin(
            value = "0",
            message = "Phụ thu size L không được âm"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Phụ thu size L tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal sizeLExtraPrice =
            new BigDecimal("7000");

        private boolean sizeXlActive = true;

        @NotNull(message = "Vui lòng nhập phụ thu size XL")
        @DecimalMin(
            value = "0",
            message = "Phụ thu size XL không được âm"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Phụ thu size XL tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal sizeXlExtraPrice =
            new BigDecimal("12000");

        private List<Long> toppingIds = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class CheckoutForm {

        @NotNull(message = "Vui lòng chọn địa chỉ")
        @Positive
        private Long addressId;

        @NotNull(message = "Vui lòng chọn nhà vận chuyển")
        @Positive
        private Long carrierId;

        @NotEmpty(message = "Vui lòng chọn sản phẩm")
        private List<@NotNull @Positive Long> itemIds =
            new ArrayList<>();

        @Size(max = 40)
        private String voucherCode;

        @Size(
            max = 500,
            message = "Ghi chú không quá 500 ký tự"
        )
        private String note;

        @NotNull
        private vn.iotstar.entity.DomainEnums.PaymentMethod
            paymentMethod =
                vn.iotstar.entity.DomainEnums.PaymentMethod.COD;
    }

    @Getter
    @Setter
    public static class CarrierForm {

        @NotBlank(message = "Vui lòng nhập tên nhà vận chuyển")
        @Size(
            min = 2,
            max = 150,
            message = "Tên nhà vận chuyển dài từ 2 đến 150 ký tự"
        )
        private String name;

        @NotNull(message = "Vui lòng nhập phí vận chuyển")
        @DecimalMin(
            value = "0",
            message = "Phí vận chuyển không được âm"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Phí vận chuyển tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal shippingFee;

        private boolean active = true;
    }

    @Getter
    @Setter
    public static class VoucherForm {

        @NotBlank(message = "Vui lòng nhập mã giảm giá")
        @Size(
            min = 3,
            max = 40,
            message = "Mã giảm giá phải có từ 3 đến 40 ký tự"
        )
        @Pattern(
            regexp = "^[A-Za-z0-9_-]+$",
            message = "Mã chỉ được gồm chữ, số, dấu gạch ngang hoặc gạch dưới"
        )
        private String code;

        @NotBlank(message = "Vui lòng nhập tên chương trình")
        @Size(
            max = 180,
            message = "Tên chương trình không được quá 180 ký tự"
        )
        private String name;

        @NotNull(message = "Vui lòng chọn loại giảm giá")
        private VoucherType type = VoucherType.PERCENT;

        @NotNull(message = "Vui lòng nhập giá trị giảm")
        @DecimalMin(
            value = "0.01",
            message = "Giá trị giảm phải lớn hơn 0"
        )
        private BigDecimal discountValue;

        @NotNull(message = "Vui lòng nhập giá trị đơn tối thiểu")
        @DecimalMin(
            value = "0",
            message = "Giá trị đơn tối thiểu không được âm"
        )
        private BigDecimal minimumOrder = BigDecimal.ZERO;

        @DecimalMin(
            value = "0.01",
            message = "Mức giảm tối đa phải lớn hơn 0"
        )
        private BigDecimal maxDiscount;

        @Min(value = 0, message = "Số lượng không được âm")
        private int quantity;

        @Min(
            value = 1,
            message = "Mỗi người phải được dùng ít nhất 1 lượt"
        )
        private int maxUsesPerUser = 1;

        @NotNull(message = "Vui lòng chọn thời gian bắt đầu")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime startsAt;

        @NotNull(message = "Vui lòng chọn thời gian kết thúc")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime endsAt;

        private boolean active = true;
    }

    @Getter
    @Setter
    public static class PromotionForm {

        @NotBlank(message = "Vui lòng nhập tên khuyến mãi")
        @Size(
            min = 3,
            max = 180,
            message = "Tên khuyến mãi phải có từ 3 đến 180 ký tự"
        )
        private String name;

        @NotNull(message = "Vui lòng chọn loại khuyến mãi")
        private PromotionType type;

        @NotNull(message = "Vui lòng nhập mức giảm")
        @DecimalMin(
            value = "0.01",
            message = "Mức giảm phải lớn hơn hoặc bằng 0,01"
        )
        @Digits(
            integer = 12,
            fraction = 2,
            message = "Mức giảm tối đa 12 chữ số nguyên và 2 chữ số thập phân"
        )
        private BigDecimal discountValue;

        @Positive(message = "Sản phẩm được chọn không hợp lệ")
        private Long productId;

        @NotNull(message = "Vui lòng chọn thời gian bắt đầu")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime startsAt;

        @NotNull(message = "Vui lòng chọn thời gian kết thúc")
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime endsAt;

        private boolean active = true;
    }
}