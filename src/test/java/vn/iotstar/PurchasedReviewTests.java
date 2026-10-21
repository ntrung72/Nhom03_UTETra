package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.dto.WebForms.ReviewForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.repository.ReviewRepository;
import vn.iotstar.service.EngagementService;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PurchasedReviewTests extends CheckoutTestFixtures {

	@Autowired
	EngagementService engagement;

	@Autowired
	ReviewRepository reviews;

	@MockitoBean
	MediaStorageService storage;

	String text = "Sản phẩm được đóng gói cẩn thận, trà thơm ngon và giao hàng đúng hẹn.";

	@BeforeEach
	void media() {
		when(storage.upload(any(MultipartFile.class), eq("reviews"))).thenReturn("/uploads/reviews/test.png");
	}

	CustomerOrder delivered() {
		var order = orders.checkout(customer, form(add(tea, medium, 1).getId())).getFirst();

		order.setStatus(OrderStatus.DELIVERED);
		order.setPaymentStatus(PaymentStatus.PAID);

		return orderRepository.saveAndFlush(order);
	}

	ReviewForm review(int rating) {
		var form = new ReviewForm();
		form.setRating(rating);
		form.setContent(text);

		return form;
	}

	String route(CustomerOrder order) {
		return "/orders/" + order.getId() + "/items/" + order.getItems().getFirst().getId() + "/review";
	}

	@Test
	void deliveredPurchaseCreatesReviewWithMediaAndUpdatesProductAggregates() {
		var order = delivered();
		var itemId = order.getItems().getFirst().getId();

		var saved = engagement.addReview(customer, order.getId(), itemId, review(4),
				new MultipartFile[] { MediaTestFiles.image("media", "x.png") });

		assertThat(saved.getMedia()).hasSize(1);

		assertThat(saved.getUser().getId()).isEqualTo(customer.getId());

		assertThat(tea.getReviewCount()).isEqualTo(1);

		assertThat(tea.getAverageRating()).isEqualByComparingTo("4.00");

		assertThat(engagement.reviewedItemIds(customer, order.getId())).containsExactly(itemId);
	}

	@Test
	void eachOrderItemCanOnlyBeReviewedOnce() {
		var order = delivered();
		var itemId = order.getItems().getFirst().getId();

		engagement.addReview(customer, order.getId(), itemId, review(5), null);

		assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), itemId, review(1), null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(reviews.countByProductId(tea.getId())).isEqualTo(1);
	}

	@Test
	void ratingAndTrimmedContentAreValidatedInService() {
		var order = delivered();
		var itemId = order.getItems().getFirst().getId();

		for (int rating : new int[] { 0, 6 }) {
			assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), itemId, review(rating), null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		var form = review(5);
		form.setContent(" ".repeat(60));

		assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), itemId, form, null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(reviews.count()).isZero();

		verifyNoInteractions(storage);
	}

	@Test
	void foreignBuyerOrItemAndUndeliveredOrderCannotBeReviewed() {
		var order = delivered();
		var itemId = order.getItems().getFirst().getId();

		assertThatThrownBy(() -> engagement.addReview(other, order.getId(), itemId, review(5), null))
				.isInstanceOf(ResponseStatusException.class);

		assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), Long.MAX_VALUE, review(5), null))
				.isInstanceOf(ResponseStatusException.class);

		order.setStatus(OrderStatus.SHIPPING);
		orderRepository.saveAndFlush(order);

		assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), itemId, review(5), null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(reviews.count()).isZero();
	}

	@Test
	void invalidMediaIsRejectedBeforeAnyUpload() {
		var order = delivered();
		var itemId = order.getItems().getFirst().getId();

		MultipartFile[] files = { MediaTestFiles.image("media", "ok.png"),
				new MockMultipartFile("media", "bad.html", "text/html", new byte[] { 1 }) };

		assertThatThrownBy(() -> engagement.addReview(customer, order.getId(), itemId, review(5), files))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(reviews.count()).isZero();

		verifyNoInteractions(storage);
	}

	@Test
	void twoPurchasedItemsProduceAccurateAverageAndRatingFilter() {
		var first = delivered();
		var second = delivered();

		engagement.addReview(customer, first.getId(), first.getItems().getFirst().getId(), review(5), null);

		engagement.addReview(customer, second.getId(), second.getItems().getFirst().getId(), review(2), null);

		assertThat(tea.getAverageRating()).isEqualByComparingTo("3.50");

		assertThat(tea.getReviewCount()).isEqualTo(2);
	}

	@Test
	void mvcSubmissionRequiresCsrfAndPublicPageEscapesReviewText() throws Exception {

		var order = delivered();

		mvc.perform(multipart(route(order)).with(user(customer.getUsername()).roles("USER")).param("rating", "5")
				.param("content", text)).andExpect(status().isForbidden());

		mvc.perform(multipart(route(order)).with(user(customer.getUsername()).roles("USER")).with(csrf())
				.param("rating", "5").param("content", text + "<script>alert(1)</script>"))
				.andExpect(redirectedUrl("/orders/" + order.getId()));

		mvc.perform(get("/products/" + tea.getId())).andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;script&gt;")))
				.andExpect(content().string(not(containsString("<script>alert(1)</script>"))));

		mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Đã đánh giá")));
	}

	@Test
	void invalidFormAndIdorDoNotWriteReviews() throws Exception {
		var order = delivered();

		mvc.perform(multipart(route(order)).with(user(customer.getUsername()).roles("USER")).with(csrf())
				.param("rating", "6").param("content", "ngắn")).andExpect(flash().attributeExists("error"));

		mvc.perform(multipart(route(order)).with(user(other.getUsername()).roles("USER")).with(csrf())
				.param("rating", "5").param("content", text)).andExpect(status().isNotFound());

		assertThat(reviews.count()).isZero();
	}
}