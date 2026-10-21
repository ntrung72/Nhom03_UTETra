package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.ReturnRequestService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerReturnTests extends CheckoutTestFixtures {

	@Autowired
	ReturnRequestService returns;

	@Autowired
	ReturnRequestRepository requests;

	@MockitoBean
	MediaStorageService storage;

	@BeforeEach
	void media() {
		other.setRole(Role.VENDOR);
		users.saveAndFlush(other);

		when(storage.upload(any(MultipartFile.class), eq("returns"))).thenReturn("private:returns/proof.png");
	}

	CustomerOrder delivered() {
		var order = orders.checkout(customer, form(add(tea, medium, 1).getId())).getFirst();

		order.setStatus(OrderStatus.DELIVERED);
		order.setPaymentStatus(PaymentStatus.PAID);

		return orderRepository.saveAndFlush(order);
	}

	MockMultipartFile proof() {
		return MediaTestFiles.image("evidence", "proof.png");
	}

	void submit(CustomerOrder order, String amount) throws Exception {
		mvc.perform(multipart("/orders/" + order.getId() + "/return").file(proof())
				.with(user(customer.getUsername()).roles("USER")).with(csrf()).param("reason", "DAMAGED")
				.param("description", "Sản phẩm bị đổ và hư hỏng khi nhận hàng.").param("refundAmount", amount))
				.andExpect(redirectedUrl("/orders/" + order.getId()));
	}

	@Test
	void deliveredPaidOrderHasReturnFormAndSuccessfulRequestIsVisibleInDetail() throws Exception {

		var order = delivered();

		mvc.perform(get("/orders/" + order.getId() + "/return").with(user(customer.getUsername()).roles("USER")))
				.andExpect(status().isOk()).andExpect(view().name("orders/return-form"));

		submit(order, order.getTotalAmount().toPlainString());

		assertThat(returns.getForUser(customer, order.getId()).getMedia()).hasSize(1);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);

		mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
				.andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("Chờ shop xử lý")));

		mvc.perform(get("/orders/" + order.getId() + "/return").with(user(customer.getUsername()).roles("USER")))
				.andExpect(redirectedUrl("/orders/" + order.getId()));
	}

	@Test
	void rejectsForgedRefundAndMissingEvidenceWithoutCreatingRequest() throws Exception {

		var order = delivered();

		mvc.perform(multipart("/orders/" + order.getId() + "/return").file(proof())
				.with(user(customer.getUsername()).roles("USER")).with(csrf()).param("reason", "DAMAGED")
				.param("description", "Bằng chứng về sản phẩm đã bị hỏng khi giao.").param("refundAmount", "1"))
				.andExpect(status().isOk()).andExpect(view().name("orders/return-form"))
				.andExpect(model().attributeHasErrors("returnRequestForm"));

		assertThat(requests.existsByOrderId(order.getId())).isFalse();

		verifyNoInteractions(storage);
	}

	@Test
	void missingEvidenceAndBlankDescriptionAreReportedOnForm() throws Exception {

		var order = delivered();

		for (String text : new String[] { " ".repeat(30), "Sản phẩm bị hỏng khi nhận hàng, xin kiểm tra lại." }) {
			mvc.perform(multipart("/orders/" + order.getId() + "/return")
					.with(user(customer.getUsername()).roles("USER")).with(csrf()).param("reason", "DAMAGED")
					.param("description", text).param("refundAmount", order.getTotalAmount().toString()))
					.andExpect(status().isOk()).andExpect(model().attributeHasErrors("returnRequestForm"));
		}

		assertThat(requests.existsByOrderId(order.getId())).isFalse();

		verifyNoInteractions(storage);
	}

	@Test
	void onlyOwnerCanOpenSubmitOrConfirmReturnAndPostRequiresCsrf() throws Exception {

		var order = delivered();

		for (String suffix : new String[] { "/return", "/return/shipped" }) {
			mvc.perform(post("/orders/" + order.getId() + suffix).with(user(other.getUsername()).roles("VENDOR"))
					.with(csrf())).andExpect(status().isNotFound());
		}

		mvc.perform(get("/orders/" + order.getId() + "/return").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isNotFound());

		mvc.perform(multipart("/orders/" + order.getId() + "/return").file(proof())
				.with(user(customer.getUsername()).roles("USER"))).andExpect(status().isForbidden());
	}

	@Test
	void confirmsReturningOnlyAfterApprovalAndShowsNewStatus() throws Exception {

		var order = delivered();

		submit(order, order.getTotalAmount().toString());

		var request = returns.getForUser(customer, order.getId());

		mvc.perform(post("/orders/" + order.getId() + "/return/shipped")
				.with(user(customer.getUsername()).roles("USER")).with(csrf()))
				.andExpect(flash().attributeExists("error"));

		returns.decide(other, request.getId(), ReturnStatus.APPROVED, order.getTotalAmount(),
				"Shop đồng ý nhận lại hàng.");

		mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
				.andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("Tôi đã gửi hàng trả")));

		mvc.perform(post("/orders/" + order.getId() + "/return/shipped")
				.with(user(customer.getUsername()).roles("USER")).with(csrf()))
				.andExpect(redirectedUrl("/orders/" + order.getId()));

		assertThat(returns.getForUser(customer, order.getId()).getStatus()).isEqualTo(ReturnStatus.RETURNING);
	}

	@Test
	void newOrUnpaidOrdersCannotOpenReturnForm() throws Exception {
		var order = delivered();

		order.setPaymentStatus(PaymentStatus.UNPAID);
		orderRepository.saveAndFlush(order);

		mvc.perform(get("/orders/" + order.getId() + "/return").with(user(customer.getUsername()).roles("USER")))
				.andExpect(redirectedUrl("/orders/" + order.getId()));

		order.setStatus(OrderStatus.NEW);
		orderRepository.saveAndFlush(order);

		mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.not(Matchers.containsString("Yêu cầu trả hàng / hoàn tiền"))));
	}
}