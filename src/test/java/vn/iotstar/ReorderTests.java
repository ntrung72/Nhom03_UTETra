package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.iotstar.service.*;
import vn.iotstar.repository.*;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class ReorderTests {
 @Test void reordersCurrentOptionsAndReportsUnavailableLines(){
  var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  var orders=mock(OrderRepository.class);var products=mock(ProductRepository.class);var sizes=mock(ProductSizeRepository.class);var toppings=mock(ProductToppingRepository.class);var carts=mock(CartService.class);
  var user=new User();user.setId(1L);var shop=new Shop();shop.setStatus(ShopStatus.APPROVED);var cat=new Category();
  var product=new Product();product.setId(2L);product.setShop(shop);product.setCategory(cat);product.setStock(5);product.setPrice(new BigDecimal("40000"));
  var order=new CustomerOrder();order.setUser(user);var item=new OrderItem();item.setProduct(product);item.setProductName("Trà");item.setSize("M");item.setSugarLevel(SugarLevel.values()[0]);item.setIceLevel(IceLevel.values()[0]);item.setQuantity(2);order.getItems().add(item);
  when(orders.findByIdAndUserId(3L,1L)).thenReturn(Optional.of(order));when(products.findById(2L)).thenReturn(Optional.of(product));
  var size=new ProductSize();size.setId(4L);size.setCode("M");when(sizes.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(2L)).thenReturn(List.of(size));
  when(carts.get(user)).thenReturn(new Cart());when(toppings.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(2L)).thenReturn(List.of());
  var service=new ReorderService(tx,orders,products,sizes,toppings,carts);var result=service.reorder(user,3L);
  assertThat(result.addedLines()).isEqualTo(1);verify(carts).add(user,2L,2,4L,item.getSugarLevel(),item.getIceLevel(),List.of());
  product.setStock(0);assertThat(service.reorder(user,3L).skipped()).hasSize(1);
 }
 @Test void cannotReorderAnOrderOwnedByAnotherUser(){
  var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  var orders=mock(OrderRepository.class);var user=new User();user.setId(1L);when(orders.findByIdAndUserId(2L,1L)).thenReturn(Optional.empty());
  var service=new ReorderService(tx,orders,mock(ProductRepository.class),mock(ProductSizeRepository.class),mock(ProductToppingRepository.class),mock(CartService.class));
  assertThatThrownBy(()->service.reorder(user,2L)).isInstanceOf(IllegalArgumentException.class);
 }
}
