package vn.iotstar.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vn.iotstar.service.ProductOptionService;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@RequiredArgsConstructor
public class DemoToppingCatalogBackfill implements ApplicationRunner {
    private final ProductOptionService productOptionService;

    @Override
    public void run(ApplicationArguments args) {
        productOptionService.reconcileDemoToppingCatalog();
    }
}
