package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.UserRepository;

/** DB trống không được tạo tài khoản demo khi seed bị tắt. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:step35_no_seed;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.seed-data=false", "app.vnpay.scheduler-enabled=false"
})
class Step35NoSeedTests {
    @Autowired private UserRepository users;
    @Autowired private ProductRepository products;

    @Test
    void cleanDatabaseHasNoDemoUsersOrProducts() {
        assertThat(users.count()).isZero();
        assertThat(products.count()).isZero();
    }
}
