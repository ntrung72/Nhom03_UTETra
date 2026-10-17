package vn.iotstar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.RevenueReportService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.VendorService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ReturnRequestService;

@Controller
@RequestMapping("/vendor")
@RequiredArgsConstructor
public class VendorController {

	private final CurrentUserService currentUserService;
	private final VendorService vendorService;
	private final CustomUserDetailsService userDetailsService;
	private final ProductOptionService productOptionService;
	private final ProductGalleryService productGalleryService;
	private final OrderService orderService;
	private final ReturnRequestService returnRequestService;
	private final DashboardService dashboardService;
	private final RevenueReportService revenueReportService;
	private final ProductRepository productRepository;

	@ModelAttribute("currentUser")
	public User currentUser() {
		return currentUserService.optional().orElse(null);
	}

	@GetMapping({ "", "/" })
	public String home(
			@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "range", required = false) String range,
			@RequestParam(name = "shopId", required = false) Long shopId,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "q", required = false) String q,
			@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);
		model.addAttribute("shop", shop);
		if (shop != null) {
			var dashboardRange = dashboardService.range(range, from, to);
			model.addAttribute("dashboardRange", dashboardRange);
			var revenue = orderService.revenueSummary(shop);
			model.addAttribute("revenue", revenue.sellerRevenue());
			model.addAttribute("grossRevenue", revenue.grossRevenue());
			model.addAttribute("platformCommission", revenue.platformRevenue());
			model.addAttribute("productCount", productRepository.countByShopId(shop.getId()));
			model.addAttribute("newOrderCount", orderService.shopCount(shop, OrderStatus.NEW));
			model.addAttribute("shippingCount", orderService.shopCount(shop, OrderStatus.SHIPPING));
			model.addAttribute("dashboard", dashboardService.forVendor(shop, dashboardRange));
			var filter = revenueReportService.filter(dashboardRange.from(), dashboardRange.to(), shopId, status);
			var report = revenueReportService.report(user, filter);
			model.addAttribute("report", report);
			model.addAttribute("reportRows", revenueReportService.tableRows(report, q, page));
			model.addAttribute("reportQuery", q);
			model.addAttribute("reportOrderUrl", "/vendor/orders");
			model.addAttribute("reportShops", revenueReportService.availableShops(user));
			model.addAttribute("reportDashboardUrl", "/vendor");
			model.addAttribute("reportExportUrl", "/vendor/reports/revenue.csv");
			model.addAttribute("reportStatuses", OrderStatus.values());
		}
		return "vendor/dashboard";
	}

	@GetMapping("/register")
	public String register(Model model) {
		User user = currentUserService.require();

		if (vendorService.findShop(user) != null) {
			return "redirect:/vendor/shop/edit";
		}

		ShopForm form = new ShopForm();
		form.setEmail(user.getEmail());
		form.setOpeningTime(LocalTime.of(8, 0));
		form.setClosingTime(LocalTime.of(22, 0));

		model.addAttribute("shopForm", form);
		model.addAttribute("editing", false);

		return "vendor/shop-form";
	}

	@PostMapping("/register")
	public String register(@Valid @ModelAttribute("shopForm") ShopForm form, BindingResult result,
			@RequestParam(name = "image", required = false) MultipartFile image, Model model,
			RedirectAttributes redirect, HttpServletRequest request, HttpServletResponse response) {

		model.addAttribute("editing", false);

		if (result.hasErrors()) {
			return "vendor/shop-form";
		}

		User user = currentUserService.require();

		try {
			vendorService.registerShop(user, form, image);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			result.reject("shop.registration", exception.getMessage());
			return "vendor/shop-form";
		}

		refreshAuthentication(user.getUsername(), request, response);

		redirect.addFlashAttribute("success", "Đăng ký cửa hàng thành công. Vui lòng chờ duyệt.");

		return "redirect:/vendor/shop/edit";
	}

	@GetMapping("/shop/edit")
	public String editShop(Model model) {
		Shop shop = vendorService.findShop(currentUserService.require());

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		ShopForm form = new ShopForm();
		form.setName(shop.getName());
		form.setDescription(shop.getDescription());
		form.setPhone(shop.getPhone());
		form.setEmail(shop.getEmail());
		form.setAddress(shop.getAddress());
		form.setProvince(shop.getProvince());
		form.setDistrict(shop.getDistrict());
		form.setWard(shop.getWard());
		form.setLatitude(shop.getLatitude());
		form.setLongitude(shop.getLongitude());
		form.setOpeningTime(shop.getOpeningTime());
		form.setClosingTime(shop.getClosingTime());

		model.addAttribute("shopForm", form);
		model.addAttribute("shop", shop);
		model.addAttribute("editing", true);

		return "vendor/shop-form";
	}

	@PostMapping("/shop/edit")
	public String editShop(@Valid @ModelAttribute("shopForm") ShopForm form, BindingResult result,
			@RequestParam(name = "image", required = false) MultipartFile image, Model model,
			RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		model.addAttribute("shop", shop);
		model.addAttribute("editing", true);

		if (result.hasErrors()) {
			return "vendor/shop-form";
		}

		try {
			vendorService.updateShop(user, form, image);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			result.reject("shop.update", exception.getMessage());
			return "vendor/shop-form";
		}

		redirect.addFlashAttribute("success", "Đã cập nhật thông tin cửa hàng.");

		return "redirect:/vendor/shop/edit";
	}

	@GetMapping("/products")
	public String products(@RequestParam(name = "q", required = false) String q,
			@RequestParam(name = "page", defaultValue = "0") int page, Model model) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		model.addAttribute("shop", shop);
		model.addAttribute("page", vendorService.products(user, q, page));
		model.addAttribute("q", q);

		return "vendor/products";
	}

	@GetMapping("/inventory")
	public String inventory(@RequestParam(name = "productId", required = false) Long productId,
			@RequestParam(name = "page", defaultValue = "0") int page, Model model, RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		try {
			model.addAttribute("transactions", vendorService.inventoryTransactions(user, productId, page));
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
			return "redirect:/vendor/inventory";
		}

		model.addAttribute("shop", shop);
		model.addAttribute("products", vendorService.inventoryProducts(user));
		model.addAttribute("lowStockProducts", vendorService.lowStockProducts(user));
		model.addAttribute("lowStockThreshold", Product.LOW_STOCK_THRESHOLD);
		model.addAttribute("selectedProductId", productId);

		return "vendor/inventory";
	}

	@GetMapping("/products/new")
	public String newProduct(Model model) {
		Shop shop = vendorService.findShop(currentUserService.require());

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		ProductForm form = new ProductForm();
		productOptionService.applyNewProductDefaults(form);

		model.addAttribute("productForm", form);
		prepareProductForm(model, shop, null);

		return "vendor/product-form";
	}

	@GetMapping("/products/{id}/edit")
	public String editProduct(@PathVariable("id") Long id, Model model, RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		Product product;

		try {
			product = vendorService.productForEdit(user, id);
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
			return "redirect:/vendor/products";
		}

		ProductForm form = new ProductForm();
		form.setVersion(product.getVersion());
		form.setName(product.getName());
		form.setSku(product.getSku());
		form.setCategoryId(product.getCategory().getId());
		form.setDescription(product.getDescription());
		form.setPrice(product.getPrice());
		form.setStock(product.getStock());
		form.setStatus(product.getStatus());

		productOptionService.loadConfiguration(product, form);

		model.addAttribute("productForm", form);
		model.addAttribute("product", product);
		prepareProductForm(model, shop, id);

		return "vendor/product-form";
	}

	@PostMapping({ "/products/new", "/products/{id}/edit" })
	public String saveProduct(@PathVariable(name = "id", required = false) Long id,
			@Valid @ModelAttribute("productForm") ProductForm form, BindingResult result,
			@RequestParam(name = "image", required = false) MultipartFile image,
			@RequestParam(name = "galleryImages", required = false) MultipartFile[] galleryImages,
			@RequestParam(name = "removeImageIds", required = false) List<Long> removeImageIds,
			@RequestParam(name = "galleryOrder", required = false) List<Long> galleryOrder,
			@RequestParam(name = "primaryImageId", required = false) Long primaryImageId, Model model,
			RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		if (id != null) {
			try {
				Product product = vendorService.productForEdit(user, id);
				model.addAttribute("product", product);
			} catch (IllegalArgumentException exception) {
				redirect.addFlashAttribute("error", exception.getMessage());
				return "redirect:/vendor/products";
			}
		}

		prepareProductForm(model, shop, id);

		if (result.hasErrors()) {
			return "vendor/product-form";
		}

		try {
			vendorService.saveProduct(user, id, form, image, galleryImages, removeImageIds, galleryOrder,
					primaryImageId);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			result.reject("product.save", exception.getMessage());

			if (id != null) {
				model.addAttribute("product", vendorService.productForEdit(user, id));
			}

			prepareProductForm(model, shop, id);

			return "vendor/product-form";
		}

		redirect.addFlashAttribute("success", id == null ? "Đã thêm sản phẩm." : "Đã cập nhật sản phẩm.");

		return "redirect:/vendor/products";
	}

	@PostMapping("/products/{id}/toggle")
	public String toggleProduct(@PathVariable("id") Long id, RedirectAttributes redirect) {

		try {
			vendorService.toggleProduct(currentUserService.require(), id);

			redirect.addFlashAttribute("success", "Đã thay đổi trạng thái hiển thị sản phẩm.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		return "redirect:/vendor/products";
	}

	@GetMapping("/orders")
	public String orders(@RequestParam(name = "status", required = false) OrderStatus status,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "orderId", required = false) Long orderId, Model model) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		var orders = orderService.forVendor(user, status, page);
		var allowedTransitions = new LinkedHashMap<Long, List<OrderStatus>>();

		orders.forEach(
				order -> allowedTransitions.put(order.getId(), orderService.vendorTransitions(order.getStatus())));

		model.addAttribute("shop", shop);
		model.addAttribute("orders", orders);
		model.addAttribute("statuses", OrderStatus.values());
		model.addAttribute("selectedStatus", status);
		model.addAttribute("allowedTransitions", allowedTransitions);
		model.addAttribute("orderStats", orderService.vendorOrderStats(user));

		if (orderId != null) {
			var selectedOrder = orderService.getForVendor(user, orderId);

			model.addAttribute("selectedOrder", selectedOrder);
			model.addAttribute("orderHistory", orderService.vendorStatusHistory(user, orderId));
			model.addAttribute("selectedTransitions", orderService.vendorTransitions(selectedOrder.getStatus()));
		}

		return "vendor/orders";
	}

	@PostMapping("/orders/{id}/status")
	public String updateOrder(@PathVariable("id") Long id, @RequestParam(name = "status") OrderStatus status,
			@RequestParam(name = "note", required = false) String note,
			@RequestParam(name = "filterStatus", required = false) OrderStatus filterStatus,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "showDetail", defaultValue = "false") boolean showDetail,
			RedirectAttributes redirect) {

		User user = currentUserService.require();

		try {
			orderService.vendorUpdate(user, id, status, note);

			redirect.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		if (filterStatus != null) {
			redirect.addAttribute("status", filterStatus.name());
		}

		redirect.addAttribute("page", Math.max(0, page));

		if (showDetail) {
			redirect.addAttribute("orderId", id);
		}

		return "redirect:/vendor/orders";
	}

	@GetMapping("/returns")
	public String returnRequests(@RequestParam(name = "status", required = false) ReturnStatus status,
			@RequestParam(name = "page", defaultValue = "0") int page, Model model) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		model.addAttribute("shop", shop);
		model.addAttribute("requests", returnRequestService.forShop(user, status, page));
		model.addAttribute("returnStatuses", ReturnStatus.values());
		model.addAttribute("selectedReturnStatus", status);

		return "vendor/returns";
	}

	@GetMapping("/returns/{id}")
	public String returnDetail(@PathVariable("id") Long id, Model model) {

		User user = currentUserService.require();
		var request = returnRequestService.getForShop(user, id);

		boolean canConfirmRefund = user.getRole() == Role.ADMIN
				|| (user.getRole() == Role.VENDOR && request.getShop().getOwner().getId().equals(user.getId()));

		model.addAttribute("shop", request.getShop());
		model.addAttribute("returnRequest", request);
		model.addAttribute("canConfirmRefund", canConfirmRefund);

		return "vendor/return-detail";
	}

	@PostMapping("/returns/{id}/decision")
	public String decideReturn(@PathVariable("id") Long id, @RequestParam(name = "decision") ReturnStatus decision,
			@RequestParam(name = "refundAmount", required = false) BigDecimal refundAmount,
			@RequestParam(name = "note", required = false) String note, RedirectAttributes redirect) {

		try {
			returnRequestService.decide(currentUserService.require(), id, decision, refundAmount, note);

			redirect.addFlashAttribute("success", "Đã xử lý yêu cầu trả hàng.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		return "redirect:/vendor/returns/" + id;
	}

	@PostMapping("/returns/{id}/status")
	public String updateReturnStatus(@PathVariable("id") Long id, @RequestParam(name = "status") ReturnStatus status,
			@RequestParam(name = "note", required = false) String note, RedirectAttributes redirect) {

		try {
			returnRequestService.vendorTransition(currentUserService.require(), id, status, note);

			redirect.addFlashAttribute("success", "Đã cập nhật trạng thái xử lý trả hàng.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		return "redirect:/vendor/returns/" + id;
	}

	@PostMapping("/returns/{id}/refund")
	public String confirmRefund(@PathVariable("id") Long id,
			@RequestParam(name = "refundReference", required = false) String refundReference,
			@RequestParam(name = "refundNote", required = false) String refundNote, RedirectAttributes redirect) {

		try {
			returnRequestService.confirmRefund(currentUserService.require(), id, refundReference, refundNote);

			redirect.addFlashAttribute("success", "Đã ghi nhận xác nhận hoàn tiền.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		return "redirect:/vendor/returns/" + id;
	}

	@GetMapping("/promotions")
	public String promotions(Model model) {
		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		model.addAttribute("shop", shop);
		model.addAttribute("promotions", vendorService.promotions(user));

		return "vendor/promotions";
	}

	@GetMapping("/promotions/new")
	public String newPromotion(Model model) {
		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);

		PromotionForm form = new PromotionForm();
		form.setType(PromotionType.PRODUCT_PERCENT);
		form.setStartsAt(now);
		form.setEndsAt(now.plusDays(7));

		preparePromotionForm(model, user, shop, form, null);

		return "vendor/promotion-form";
	}

	@GetMapping("/promotions/{id}/edit")
	public String editPromotion(@PathVariable("id") Long id, Model model, RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		Promotion promotion;

		try {
			promotion = vendorService.promotionForEdit(user, id);
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
			return "redirect:/vendor/promotions";
		}

		PromotionForm form = new PromotionForm();
		form.setName(promotion.getName());
		form.setType(promotion.getType());
		form.setDiscountValue(promotion.getDiscountValue());
		form.setProductId(promotion.getProduct() == null ? null : promotion.getProduct().getId());
		form.setStartsAt(promotion.getStartsAt());
		form.setEndsAt(promotion.getEndsAt());
		form.setActive(promotion.isActive());

		preparePromotionForm(model, user, shop, form, promotion);

		return "vendor/promotion-form";
	}

	@PostMapping("/promotions/save")
	public String savePromotion(@RequestParam(name = "id", required = false) Long id,
			@Valid @ModelAttribute("promotionForm") PromotionForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {

		User user = currentUserService.require();
		Shop shop = vendorService.findShop(user);

		if (shop == null) {
			return "redirect:/vendor/register";
		}

		Promotion promotion = null;

		if (id != null) {
			try {
				promotion = vendorService.promotionForEdit(user, id);
			} catch (IllegalArgumentException exception) {
				redirect.addFlashAttribute("error", exception.getMessage());
				return "redirect:/vendor/promotions";
			}
		}

		preparePromotionForm(model, user, shop, form, promotion);

		if (result.hasErrors()) {
			return "vendor/promotion-form";
		}

		try {
			vendorService.savePromotion(user, id, form);
		} catch (IllegalArgumentException exception) {
			result.reject("promotion.invalid", exception.getMessage());
			return "vendor/promotion-form";
		}

		redirect.addFlashAttribute("success",
				id == null ? "Đã tạo chương trình khuyến mãi." : "Đã cập nhật chương trình khuyến mãi.");

		return "redirect:/vendor/promotions";
	}

	@PostMapping("/promotions/{id}/delete")
	public String deletePromotion(@PathVariable("id") Long id, RedirectAttributes redirect) {

		User user = currentUserService.require();

		if (vendorService.findShop(user) == null) {
			return "redirect:/vendor/register";
		}

		try {
			vendorService.deletePromotion(user, id);

			redirect.addFlashAttribute("success", "Đã xóa chương trình khuyến mãi.");
		} catch (IllegalArgumentException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}

		return "redirect:/vendor/promotions";
	}

	private void prepareProductForm(Model model, Shop shop, Long id) {

		model.addAttribute("shop", shop);
		model.addAttribute("editing", id != null);
		model.addAttribute("productId", id);
		model.addAttribute("categories", vendorService.activeCategories());
		model.addAttribute("statuses", ProductStatus.values());
		model.addAttribute("toppings", productOptionService.availableToppings());
		model.addAttribute("sugarLevels", SugarLevel.values());
		model.addAttribute("iceLevels", IceLevel.values());

		Product product = (Product) model.getAttribute("product");

		model.addAttribute("gallery", product == null ? List.of() : productGalleryService.images(product));
	}

	private void preparePromotionForm(Model model, User user, Shop shop, PromotionForm form, Promotion promotion) {

		model.addAttribute("shop", shop);
		model.addAttribute("promotionForm", form);
		model.addAttribute("promotion", promotion);
		model.addAttribute("editing", promotion != null);
		model.addAttribute("promotionTypes", PromotionType.values());
		model.addAttribute("products", vendorService.promotionProducts(user));
	}

	private void refreshAuthentication(String username, HttpServletRequest request, HttpServletResponse response) {

		var details = userDetailsService.loadUserByUsername(username);

		var authentication = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());

		var previous = SecurityContextHolder.getContext().getAuthentication();

		if (previous != null) {
			authentication.setDetails(previous.getDetails());
		}

		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);

		new HttpSessionSecurityContextRepository().saveContext(context, request, response);
	}
}
