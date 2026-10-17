package vn.iotstar.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI utetraOpenApi() {
        return new OpenAPI()
            .info(new Info().title("UTETra API").version("1.0")
                .description("GET /api/products công khai. Đăng nhập tại POST /api/auth/login và nhập JWT trong Authorize. GET /api/me trả tài khoản hiện tại; GET /api/cart, /api/favorites, /api/orders chỉ đọc dữ liệu của USER/VENDOR trong JWT, không nhận userId. Các danh sách hỗ trợ phân trang từ page=0."))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
