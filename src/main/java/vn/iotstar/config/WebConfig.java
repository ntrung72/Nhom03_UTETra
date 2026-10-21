package vn.iotstar.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadDir)
            .toAbsolutePath()
            .normalize()
            .toUri()
            .toString();

        if (!location.endsWith("/")) {
            location += "/";
        }

        for (String folder : new String[]{"avatars", "products", "shops", "categories", "reviews"}) {
            registry.addResourceHandler("/uploads/" + folder + "/**")
                .addResourceLocations(location + folder + "/");
        }
    }
}