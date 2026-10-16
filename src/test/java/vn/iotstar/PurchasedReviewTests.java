package vn.iotstar;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ReviewForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.ReviewRepository;
import vn.iotstar.service.EngagementService;
import vn.iotstar.service.MediaStorageService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class PurchasedReviewTests extends CheckoutTestFixtures {
    @Autowired EngagementService engagement;
    @Autowired ReviewRepository reviews;
    @MockitoBean MediaStorageService storage;
    String text = "Sản phẩm được đóng gói cẩn thận, trà thơm ngon và giao hàng đúng hẹn.";
    @BeforeEach void media() { when(storage.upload(any(MultipartFile.class), eq("reviews"))).thenReturn("/uploads/reviews/test.png"); }
    CustomerOrder delivered() {
        var o = orders.checkout(customer, form(add(tea, medium, 1).getId())).getFirst();
        o.setStatus(OrderStatus.DELIVERED); o.setPaymentStatus(PaymentStatus.PAID); return orderRepository.saveAndFlush(o);
    }
    ReviewForm review(int rating) { var f = new ReviewForm(); f.setRating(rating); f.setContent(text); return f; }
    String route(CustomerOrder o) { return "/orders/" + o.getId() + "/items/" + o.getItems().getFirst().getId() + "/review"; }

    @Test void deliveredPurchaseCreatesReviewWithMediaAndUpdatesProductAggregates() {
        var o = delivered(); var id = o.getItems().getFirst().getId();
        var saved = engagement.addReview(customer, o.getId(), id, review(4), new MultipartFile[]{new MockMultipartFile("media","x.png","image/png",new byte[]{1})});
        assertThat(saved.getMedia()).hasSize(1); assertThat(saved.getUser().getId()).isEqualTo(customer.getId());
        assertThat(tea.getReviewCount()).isEqualTo(1); assertThat(tea.getAverageRating()).isEqualByComparingTo("4.00");
        assertThat(engagement.reviewedItemIds(customer, o.getId())).containsExactly(id);
    }
    @Test void eachOrderItemCanOnlyBeReviewedOnce() {
        var o = delivered(); var id = o.getItems().getFirst().getId();
        engagement.addReview(customer, o.getId(), id, review(5), null);
        assertThatThrownBy(() -> engagement.addReview(customer, o.getId(), id, review(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reviews.countByProductId(tea.getId())).isEqualTo(1);
    }
    @Test void ratingAndTrimmedContentAreValidatedInService() {
        var o = delivered(); var id = o.getItems().getFirst().getId();
        for (int rating : new int[]{0,6}) assertThatThrownBy(() -> engagement.addReview(customer,o.getId(),id,review(rating),null)).isInstanceOf(IllegalArgumentException.class);
        var f = review(5); f.setContent(" ".repeat(60));
        assertThatThrownBy(() -> engagement.addReview(customer,o.getId(),id,f,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reviews.count()).isZero(); verifyNoInteractions(storage);
    }
    @Test void foreignBuyerOrItemAndUndeliveredOrderCannotBeReviewed() {
        var o = delivered(); var id = o.getItems().getFirst().getId();
        assertThatThrownBy(() -> engagement.addReview(other,o.getId(),id,review(5),null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> engagement.addReview(customer,o.getId(),Long.MAX_VALUE,review(5),null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        o.setStatus(OrderStatus.SHIPPING); orderRepository.saveAndFlush(o);
        assertThatThrownBy(() -> engagement.addReview(customer,o.getId(),id,review(5),null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reviews.count()).isZero();
    }
    @Test void invalidMediaIsRejectedBeforeAnyUpload() {
        var o = delivered(); var id = o.getItems().getFirst().getId();
        var files = new MultipartFile[]{new MockMultipartFile("media","ok.png","image/png",new byte[]{1}),
            new MockMultipartFile("media","bad.html","text/html",new byte[]{1})};
        assertThatThrownBy(() -> engagement.addReview(customer,o.getId(),id,review(5),files)).isInstanceOf(IllegalArgumentException.class);
        assertThat(reviews.count()).isZero(); verifyNoInteractions(storage);
    }
    @Test void twoPurchasedItemsProduceAccurateAverageAndRatingFilter() {
        var first = delivered(); var second = delivered();
        engagement.addReview(customer,first.getId(),first.getItems().getFirst().getId(),review(5),null);
        engagement.addReview(customer,second.getId(),second.getItems().getFirst().getId(),review(2),null);
        assertThat(tea.getAverageRating()).isEqualByComparingTo("3.50"); assertThat(tea.getReviewCount()).isEqualTo(2);
    }
    @Test void mvcSubmissionRequiresCsrfAndPublicPageEscapesReviewText() throws Exception {
        var o = delivered();
        mvc.perform(multipart(route(o)).with(user(customer.getUsername()).roles("USER")).param("rating","5").param("content",text))
            .andExpect(status().isForbidden());
        mvc.perform(multipart(route(o)).with(user(customer.getUsername()).roles("USER")).with(csrf()).param("rating","5").param("content",text + "<script>alert(1)</script>"))
            .andExpect(redirectedUrl("/orders/" + o.getId()));
        mvc.perform(get("/products/" + tea.getId())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert(1)</script>"))));
        mvc.perform(get("/orders/" + o.getId()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Đã đánh giá")));
    }
    @Test void invalidFormAndIdorDoNotWriteReviews() throws Exception {
        var o = delivered();
        mvc.perform(multipart(route(o)).with(user(customer.getUsername()).roles("USER")).with(csrf()).param("rating","6").param("content","ngắn"))
            .andExpect(flash().attributeExists("error"));
        mvc.perform(multipart(route(o)).with(user(other.getUsername()).roles("USER")).with(csrf()).param("rating","5").param("content",text))
            .andExpect(status().isNotFound());
        assertThat(reviews.count()).isZero();
    }
}
