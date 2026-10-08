package vn.iotstar.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;

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
    @Getter
    @Setter
    public static class ShopForm {
        @NotBlank(message = "Vui lòng nhập tên cửa hàng")
        @Size(min = 3, max = 150,
              message = "Tên cửa hàng phải có từ 3 đến 150 ký tự")
        private String name;

        @NotBlank(message = "Vui lòng nhập mô tả cửa hàng")
        @Size(min = 20, max = 1000,
              message = "Mô tả phải có từ 20 đến 1.000 ký tự")
        private String description;

        @NotBlank(message = "Vui lòng nhập số điện thoại")
        @Pattern(regexp = "^[0-9+() .-]{9,20}$",
                 message = "Số điện thoại không hợp lệ")
        private String phone;

        @NotBlank(message = "Vui lòng nhập email cửa hàng")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 160, message = "Email không được vượt quá 160 ký tự")
        private String email;

        @NotBlank(message = "Vui lòng nhập số nhà và tên đường")
        @Size(max = 255, message = "Địa chỉ không được vượt quá 255 ký tự")
        private String address;

        @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố")
        @Size(max = 120,
              message = "Tỉnh/thành phố không được vượt quá 120 ký tự")
        private String province;

        @NotBlank(message = "Vui lòng nhập quận hoặc huyện")
        @Size(max = 120,
              message = "Quận/huyện không được vượt quá 120 ký tự")
        private String district;

        @NotBlank(message = "Vui lòng nhập phường hoặc xã")
        @Size(max = 120,
              message = "Phường/xã không được vượt quá 120 ký tự")
        private String ward;

        @DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
        @DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90")
        @Digits(integer = 3, fraction = 7,
                message = "Vĩ độ chỉ được có tối đa 7 chữ số thập phân")
        private BigDecimal latitude;

        @DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
        @DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180")
        @Digits(integer = 3, fraction = 7,
                message = "Kinh độ chỉ được có tối đa 7 chữ số thập phân")
        private BigDecimal longitude;

        @NotNull(message = "Vui lòng chọn giờ mở cửa")
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime openingTime;

        @NotNull(message = "Vui lòng chọn giờ đóng cửa")
        @DateTimeFormat(pattern = "HH:mm")
        private LocalTime closingTime;
    }
}
