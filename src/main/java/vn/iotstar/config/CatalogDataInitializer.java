package vn.iotstar.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Category;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.util.SlugUtils;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.seed-data",
    havingValue = "true",
    matchIfMissing = true
)
public class CatalogDataInitializer implements CommandLineRunner {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public void run(String... args) {
        createCategory(
            "Trà sữa",
            "Trà sữa truyền thống và hiện đại"
        );

        createCategory(
            "Trà trái cây",
            "Trà tươi kết hợp trái cây"
        );

        createCategory(
            "Cà phê",
            "Cà phê rang xay và cà phê sữa"
        );

        createCategory(
            "Topping",
            "Các loại topping dùng kèm"
        );
    }

    private void createCategory(String name, String description) {
        String slug = SlugUtils.toSlug(name);

        if (categoryRepository.existsBySlug(slug)) {
            return;
        }

        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(description);
        category.setActive(true);

        categoryRepository.save(category);
    }
}