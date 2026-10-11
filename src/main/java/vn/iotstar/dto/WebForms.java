package vn.iotstar.dto;

import java.util.ArrayList;
import java.util.List;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;

public final class WebForms {
	private WebForms() {
	}

	@Getter
	@Setter
	public static class RegisterForm {
		@NotBlank
		@Size(min = 4, max = 60)
		@Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch ngang và gạch dưới")
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
        @Size(max = 72)
        private String currentPassword;
        @NotBlank(message = "Vui lòng nhập mật khẩu mới")
        @Size(min = 8, max = 72, message = "Mật khẩu mới phải có từ 8 đến 72 ký tự")
        private String newPassword;
        @NotBlank(message = "Vui lòng xác nhận mật khẩu mới")
        @Size(max = 72)
        private String confirmPassword;
    }


	@Getter
	@Setter
	public static class ShopForm {
		@NotBlank(message = "Vui lòng nhập tên cửa hàng")
		@Size(min = 3, max = 150, message = "Tên cửa hàng phải có từ 3 đến 150 ký tự")
		private String name;

		@NotBlank(message = "Vui lòng nhập mô tả cửa hàng")
		@Size(min = 20, max = 1000, message = "Mô tả phải có từ 20 đến 1.000 ký tự")
		private String description;

		@NotBlank(message = "Vui lòng nhập số điện thoại")
		@Pattern(regexp = "^[0-9+() .-]{9,20}$", message = "Số điện thoại không hợp lệ")
		private String phone;

		@NotBlank(message = "Vui lòng nhập email cửa hàng")
		@Email(message = "Email không đúng định dạng")
		@Size(max = 160, message = "Email không được vượt quá 160 ký tự")
		private String email;

		@NotBlank(message = "Vui lòng nhập số nhà và tên đường")
		@Size(max = 255, message = "Địa chỉ không được vượt quá 255 ký tự")
		private String address;

		@NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
		@Size(max = 120, message = "Tỉnh/thành phố không được vượt quá 120 ký tự")
		private String province;

		@NotBlank(message = "Vui lòng nhập quận hoặc huyện")
		@Size(max = 120, message = "Quận/huyện không được vượt quá 120 ký tự")
		private String district;

		@NotBlank(message = "Vui lòng nhập phường hoặc xã")
		@Size(max = 120, message = "Phường/xã không được vượt quá 120 ký tự")
		private String ward;

		@DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
		@DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90")
		@Digits(integer = 3, fraction = 7, message = "Vĩ độ chỉ được có tối đa 7 chữ số thập phân")
		private BigDecimal latitude;

		@DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
		@DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180")
		@Digits(integer = 3, fraction = 7, message = "Kinh độ chỉ được có tối đa 7 chữ số thập phân")
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
	public static class ProductForm {

		@NotBlank(message = "Vui lòng nhập tên sản phẩm")
		@Size(min = 3, max = 180, message = "Tên sản phẩm phải có từ 3 đến 180 ký tự")
		private String name;

		@NotBlank(message = "Vui lòng nhập mã SKU")
		@Size(min = 2, max = 60, message = "SKU phải có từ 2 đến 60 ký tự")
		@Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]*$", message = "SKU phải bắt đầu bằng chữ hoặc số và chỉ gồm chữ, số, dấu chấm, gạch ngang, gạch dưới")
		private String sku;

		@NotNull(message = "Vui lòng chọn danh mục")
		@Positive(message = "Danh mục không hợp lệ")
		private Long categoryId;

		@NotBlank(message = "Vui lòng nhập mô tả sản phẩm")
		@Size(min = 20, max = 5000, message = "Mô tả phải có từ 20 đến 5.000 ký tự")
		private String description;

		@NotNull(message = "Vui lòng nhập giá sản phẩm")
		@DecimalMin(value = "1000", message = "Giá sản phẩm phải từ 1.000 đồng")
		@Digits(integer = 12, fraction = 2, message = "Giá chỉ được có tối đa 12 chữ số nguyên và 2 chữ số thập phân")
		private BigDecimal price;

		@NotNull(message = "Vui lòng nhập số lượng tồn kho")
		@Min(value = 0, message = "Tồn kho không được âm")
		private Integer stock = 0;

		@NotNull(message = "Vui lòng chọn trạng thái sản phẩm")
		private ProductStatus status = ProductStatus.ACTIVE;
		private boolean sizeMActive = true;

		@NotNull(message = "Vui lòng nhập phụ thu size M")
		@DecimalMin(value = "0", message = "Phụ thu size M không được âm")
		@Digits(integer = 12, fraction = 2, message = "Phụ thu size M tối đa 12 chữ số nguyên và 2 chữ số thập phân")
		private BigDecimal sizeMExtraPrice = BigDecimal.ZERO;

		private boolean sizeLActive = true;

		@NotNull(message = "Vui lòng nhập phụ thu size L")
		@DecimalMin(value = "0", message = "Phụ thu size L không được âm")
		@Digits(integer = 12, fraction = 2, message = "Phụ thu size L tối đa 12 chữ số nguyên và 2 chữ số thập phân")
		private BigDecimal sizeLExtraPrice = new BigDecimal("7000");

		private boolean sizeXlActive = true;

		@NotNull(message = "Vui lòng nhập phụ thu size XL")
		@DecimalMin(value = "0", message = "Phụ thu size XL không được âm")
		@Digits(integer = 12, fraction = 2, message = "Phụ thu size XL tối đa 12 chữ số nguyên và 2 chữ số thập phân")
		private BigDecimal sizeXlExtraPrice = new BigDecimal("12000");

		private List<Long> toppingIds = new ArrayList<>();
	}
}
