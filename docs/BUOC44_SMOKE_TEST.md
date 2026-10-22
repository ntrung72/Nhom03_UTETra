# BƯỚC 44 — Checklist bảo mật triển khai và nghiệm thu UTETra

**Phạm vi:** TV3 tích hợp / hardening; TV1 và TV2 cùng review các luồng tương ứng. Không thêm hoặc chỉnh sửa nghiệp vụ Checkout, Catalog, Inventory, Return/Refund trong bước này.

## Mã nguồn và cấu hình đã bổ sung trong Bước 44

- `SecurityConfig`: mặc định hạn chế Swagger/OpenAPI cho role ADMIN; `OPENAPI_PUBLIC=true` **chỉ dùng ở môi trường local an toàn**.
- `application-dev.properties`: cấu hình local, không cache template, mở API docs để demo; không bật seed mặc định.
- `application-prod.properties`: kiểm tra schema đã tồn tại (`ddl-auto=validate`), cấm seed, Swagger private, bật cookie Secure khi triển khai qua HTTPS. **Không thể khởi tạo database trống trực tiếp bằng profile prod**; phải provision schema và chạy/kiểm tra migrations trước.
- `config/env.example`: danh sách biến môi trường và giá trị ví dụ **không phải secret thật**. File `.env`, upload và private-upload phải nằm ngoài Git và không nén vào bản release công khai.
- `templates/error/403.html`, `404.html`, `500.html`: thông báo thân thiện, không để lộ stack trace/chi tiết exception.
- `Step44ReleaseSecurityTests`: hồi quy giới hạn Swagger, private media và CSRF.

## 1. Chuẩn bị trước khi chạy release

- [ ] Java 26 (`java -version`), `mvnw.cmd -v`, `mvnw.cmd test`, ghi lại các test fail nếu có; không chỉ kiểm tra vài test được chọn.
- [ ] Nhánh `feature/management-shipper` cập nhật từ `main`; PR có TV1/TV2 review; ghi Git commit thật cho từng thành viên; **không làm giả lịch sử commit**.
- [ ] Không có secret trong `application*.properties`, `.env`, tài liệu, Git history hoặc ZIP công khai. Nếu từng lộ secret thật: xoay khóa DB/JWT/VNPay/MoMo/Cloudinary/SMTP.
- [ ] DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET (tối thiểu 32 byte), REMEMBER_ME_KEY, MAIL_*, VNPAY_*, MOMO_*, CLOUDINARY_*, WS_ALLOWED_ORIGINS được điền theo đúng môi trường.
- [ ] `OPENAPI_PUBLIC=false`, `SEED_DATA=false`, `SPRING_PROFILES_ACTIVE=prod`, HTTPS và cookie Secure.
- [ ] Với demo Swagger tại localhost, bật `SPRING_PROFILES_ACTIVE=dev` và không mở ra Internet.

## 2. Database & migration SQL Server — bắt buộc kiểm tra thủ công

- [ ] Sao lưu SQL Server DB hiện tại; thử migration/backfill trên **bản sao** trước.
- [ ] Kiểm tra schema DB mới/provision sẵn, có thể khởi động `prod` với `ddl-auto=validate` sau khi chạy schema/migrations cần thiết.
- [ ] Kiểm tra DB cũ có CartOptionsSchemaMigration, OrderStatusSchemaMigration, VnPaySchemaMigration, VoucherUsageBackfill và CommissionSnapshotBackfill: chạy lại không tạo trùng/lỗi constraint.
- [ ] So khớp Order, Payment, VoucherUsage, Product stock, ReturnRequest và commission snapshot trước/sau migrate; không chấp nhận mất dữ liệu.
- [ ] Đảm bảo backup/rollback procedure đã có và thực hành phục hồi trên môi trường test.

## 3. Security & realtime — TV3

- [ ] Guest/User/Vendor/Shipper không đọc `/v3/api-docs`; Admin đọc được; chỉ dev được bật Swagger public.
- [ ] Admin/Manager không thao tác ngoài phạm vi, Manager chỉ xem shop được gán; User/Vendor/Shipper đúng vai trò.
- [ ] POST thay đổi dữ liệu qua browser thiếu CSRF bị từ chối; MoMo IPN kiểm tra chữ ký (TV1 xác nhận).
- [ ] Guest không xem được `/private-media/**`; Owner / Vendor shop / ManagerShop / Shipper assigned / Admin đúng quyền mới xem được; `/uploads/returns/**` và `/uploads/delivery-proofs/**` không mở công khai.
- [ ] WebSocket từ origin lạ hoặc từ STOMP SEND/SUBSCRIBE trái phép bị chặn; notification chỉ phát sau DB commit, rollback không phát.
- [ ] Lỗi 403/404/500 hiển thị nhất quán, không lộ SQL, secret, stack trace, đường dẫn ổ đĩa.

## 4. Role smoke test — TV1/TV2/TV3 cùng xác nhận

| Vai trò | Các luồng bắt buộc | Người xác nhận | Trạng thái |
|---|---|---|---|
| Guest | Homepage, tìm/lọc/search suggestion, shop detail, 20 sản phẩm/trang | TV1 | CHƯA CHẠY THỰC TẾ |
| User | OTP, JWT, profile, cart, recipe, reorder, followed shops, COD, VNPay, MoMo Sandbox, voucher, loyalty, review, return | TV1 | CHƯA CHẠY THỰC TẾ |
| Vendor | Shop, product size/topping, media, stock, promotion, order workflow, return/refund, dashboard | TV2 | CHƯA CHẠY THỰC TẾ |
| Manager | ManagerShop scope, assigned shop, Product/Promotion, Order, dashboard | TV3 | CHƯA CHẠY THỰC TẾ |
| Admin | Roles, Shop approval, Voucher/Carrier, Payment/Audit, Dashboard, Manager assignment | TV3 | CHƯA CHẠY THỰC TẾ |
| Shipper | Assigned-only orders, status transition, proof media, retry, maps, dashboard | TV3 | CHƯA CHẠY THỰC TẾ |

**Quan trọng:** MoMo/VNPay Sandbox cần credential và callback public hợp lệ; kết quả từ H2 **không xác nhận** thanh toán end-to-end. Email OTP yêu cầu mailbox và SMTP thật. Các luồng trên phải được người phụ trách xác nhận trước khi báo nghiệm thu 100%.

## 5. Laptop responsive

- [ ] Chrome và Edge, 1366×768, 1440×900, 1920×1080, zoom 125%.
- [ ] Navbar menu theo role không tràn; toast thông báo dùng được qua mouse và bàn phím.
- [ ] Table Management/Vendor có scroll ngang trong thẻ, không làm viewport ngang toàn trang.
- [ ] Cart / checkout tổng tiền / payment actions không bị cắt; forms hiển thị lỗi validation.
- [ ] Shipper dashboard bộ lọc / card / actions và ảnh bằng chứng trên laptop.
- [ ] Lưu screenshot/video demo từng vai trò, lưu kết quả test Maven cùng Git commit hash trong PR.

## Kết quả môi trường tích hợp hiện tại

Bản giao mã nguồn Bước 44 **không được coi là đã nghiệm thu live Java 26, SQL Server, SMTP, VNPay và MoMo** nếu chưa thực thi các hệ thống này. Checklist này là danh sách cần chạy, không phải bản xác nhận đã chạy.
