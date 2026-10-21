package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.*;
import java.util.List;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest @ActiveProfiles("test") @Transactional
class SavedRecipeTests extends CustomerTestFixtures {
 @Autowired SavedRecipeService recipes;
 @Autowired ProductSizeRepository sizes;
 @Autowired CartService carts;
 ProductSize size(){var s=new ProductSize();s.setProduct(tea);s.setCode("M");s.setExtraPrice(new BigDecimal("5000"));return sizes.saveAndFlush(s);}
 @Test void savedOptionsUseCurrentPriceAndEnforceOwnership() throws Exception {
  var size=size();var recipe=recipes.save(customer,tea.getId(),"Ly của tôi",size.getId(),SugarLevel.values()[0],IceLevel.values()[0],List.of());
  assertThatThrownBy(()->recipes.rename(other,recipe.getId(),"Tên khác")).isInstanceOf(IllegalArgumentException.class);
  tea.setPrice(new BigDecimal("45000"));products.saveAndFlush(tea);
  recipes.addToCart(customer,recipe.getId(),2);
  assertThat(carts.get(customer).getItems()).singleElement().satisfies(line->{assertThat(line.getQuantity()).isEqualTo(2);assertThat(line.getUnitPrice()).isEqualByComparingTo("50000");});
  mvc.perform(get("/user/recipes").with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
 }
 @Test void unavailableSizeAndStockAreReportedInsteadOfSilentlyChanged(){
  var size=size();var recipe=recipes.save(customer,tea.getId(),null,size.getId(),SugarLevel.values()[0],IceLevel.values()[0],List.of());
  size.setActive(false);sizes.saveAndFlush(size);
  assertThatThrownBy(()->recipes.addToCart(customer,recipe.getId(),1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Size");
  assertThat(recipes.list(customer,0).getContent().getFirst().isAvailable()).isFalse();
 }
 @Test void quantityAndNameBoundariesAreValidated(){
  var size=size();assertThatThrownBy(()->recipes.save(customer,tea.getId(),"x",size.getId(),SugarLevel.values()[0],IceLevel.values()[0],List.of())).isInstanceOf(IllegalArgumentException.class);
  var recipe=recipes.save(customer,tea.getId(),"Món ngon",size.getId(),SugarLevel.values()[0],IceLevel.values()[0],List.of());
  assertThatThrownBy(()->recipes.addToCart(customer,recipe.getId(),11)).isInstanceOf(IllegalArgumentException.class);
  recipes.delete(customer,recipe.getId());assertThat(recipes.list(customer,0)).isEmpty();
 }
}
