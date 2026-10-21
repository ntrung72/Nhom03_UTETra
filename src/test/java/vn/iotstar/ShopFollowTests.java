package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.service.ShopFollowService;
import vn.iotstar.entity.DomainEnums.Role;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest @ActiveProfiles("test") @Transactional
class ShopFollowTests extends CustomerTestFixtures {
 @Autowired ShopFollowService follows;
 @Test void followIsIdempotentAndDoesNotChangeShopOwner() throws Exception {
  var owner=tea.getShop().getOwner().getId();assertThat(follows.follow(customer,tea.getShop().getId())).isTrue();
  assertThat(follows.follow(customer,tea.getShop().getId())).isFalse();
  assertThat(follows.followedShops(customer,0)).hasSize(1);assertThat(follows.followedShops(other,0)).isEmpty();
  assertThat(tea.getShop().getOwner().getId()).isEqualTo(owner);
  mvc.perform(get("/user/followed-shops").with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
  follows.unfollow(other,tea.getShop().getId());assertThat(follows.isFollowing(customer,tea.getShop().getId())).isTrue();
 }
 @Test void suspendedShopAndNonCustomerRoleCannotFollow(){
  tea.getShop().setEnabled(false);shops.saveAndFlush(tea.getShop());
  assertThatThrownBy(()->follows.follow(customer,tea.getShop().getId())).isInstanceOf(IllegalArgumentException.class);
  customer.setRole(Role.MANAGER);assertThatThrownBy(()->follows.follow(customer,tea.getShop().getId())).isInstanceOf(IllegalArgumentException.class);
 }
}
