package vn.iotstar.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

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

}
