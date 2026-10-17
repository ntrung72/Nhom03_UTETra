package vn.iotstar.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.RevenueReportService;

@Controller
@RequiredArgsConstructor
public class RevenueReportController {
	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private final CurrentUserService currentUserService;
	private final RevenueReportService reportService;

	@GetMapping("/vendor/reports/revenue.csv")
	ResponseEntity<byte[]> export(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long shopId, @RequestParam(required = false) String status) {
		var filter = reportService.filter(from, to, shopId, status);
		var report = reportService.report(currentUserService.require(), filter);
		String filename = "utetra-doanh-thu-" + FILE_DATE.format(filter.from()) + "-" + FILE_DATE.format(filter.to())
				+ ".csv";
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
				.body(reportService.toCsv(report));
	}
}
