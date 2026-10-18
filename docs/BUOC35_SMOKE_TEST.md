# Bước 35 – Checklist nghiệm thu tích hợp UTETra

Dùng **Java 26 + SQL Server** trên máy nhóm, chạy `mvnw.cmd test` thành công trước khi ghi nhận bàn giao. Checklist phân vai để cả nhóm xác nhận; đây là tài liệu kiểm thử, **không tạo feature mới**.

## Chuẩn bị database và migration

- [ ] Backup SQL Server hiện tại; kiểm tra startup trên bản sao DB cũ. Đơn, thanh toán, commission và voucher không mất dữ liệu.
- [ ] Khởi động hai lần trên bản sao DB cũ; các migration/backfill không tạo trùng VoucherUsage, không làm đổi commission lịch sử.
- [ ] DB demo sạch: `SEED_DATA=true` sinh đủ 6 tài khoản role, 2 shop, 30 sản phẩm, 4 danh mục, 2 carrier, 2 voucher. Ảnh demo trả HTTP 200.
- [ ] Chạy lại seed trên DB demo: không tăng số bản ghi/ghi đè dữ liệu đã sửa.
- [ ] `SEED_DATA=false` và DB trống: không tự tạo dữ liệu/tài khoản demo.
- [ ] Thử dữ liệu cũ với giỏ hàng có 2 cấu hình khác nhau cùng Product: mỗi CartItem tồn tại riêng.

## Guest / User (TV1 review)

- [ ] Trang chủ load; top Product `soldCount > 10`, sắp giảm; tìm kiếm/lọc/phân trang giữ điều kiện.
- [ ] Đăng ký OTP, resend cooldown, verify, login/logout, quên mật khẩu OTP; BCrypt và chống truy cập trái role.
- [ ] Profile/địa chỉ, danh sách yêu thích/đã xem; thêm giỏ 2 size/topping khác nhau, chọn item checkout.
- [ ] COD: subtotal/topping/voucher/carrier/commission snapshot khớp; tạo Order, trừ tồn, không xóa CartItem không chọn.
- [ ] VNPay Sandbox: trả tiền thành công, hủy, hết hạn, callback/IPN hợp lệ/bị chỉnh chữ ký; không ghi trùng payment.
- [ ] Xem lịch sử/chi tiết đơn thuộc user, hủy đơn hợp lệ/không hợp lệ, hoàn tồn/voucher, tạo return và review >= 50 ký tự có media.
- [ ] Notification center và badge; user A không xem được đơn/thông báo của user B.

## Vendor (TV2 review)

- [ ] Đăng ký Shop, Admin duyệt, Vendor chỉ sửa Shop/Product của mình; upload ảnh và gallery.
- [ ] Size/sugar/ice/topping và giá sale nhất quán từ catalog → cart → checkout.
- [ ] Quy trình `NEW → CONFIRMED → PREPARING → READY_FOR_PICKUP`, không vượt trạng thái hoặc sửa shop khác.
- [ ] Kiểm tra đơn đồng thời không bán vượt tồn kho; inventory history và tồn kho sau hủy/trả hàng.
- [ ] Duyệt return/refund, cập nhật inventory/voucher/payment; dashboard doanh thu đúng bộ lọc.

## Manager / Admin / Shipper / Realtime (TV3)

- [ ] Admin xem toàn hệ thống; Manager chỉ xem/sửa đúng các shop được gán (thử giả mạo `shopId` qua URL/POST).
- [ ] CRUD Category/Product/Topping/Promotion, Voucher/Usage, Carrier/Commission: validation và audit log.
- [ ] Quản lý payment/audit, dashboard theo ngày, lọc shop và tài khoản mới; Manager không nhìn thấy giao dịch shop khác.
- [ ] Assign SHIPPER đúng role và trạng thái, ghi OrderStatusHistory; Shipper khác không cập nhật được đơn chưa được giao.
- [ ] `READY_FOR_PICKUP → PICKED_UP → SHIPPING → DELIVERED/DELIVERY_FAILED`, thử ảnh giao hàng/lý do thất bại và điều phối lại.
- [ ] WebSocket chỉ kết nối origin cho phép; STOMP chặn subscribe/gửi trái quyền; thông báo đúng người sau commit, không phát nếu rollback.
- [ ] Trang shipper/management có dữ liệu và trạng thái rỗng; badge, toast cập nhật và reconnect hoạt động.

## Giao diện laptop và build

- [ ] Chrome/Edge: 1366×768, 1440×900, 1920×1080, zoom 100% và 125%; navbar menu đúng role, bảng có thanh cuộn, không che nút.
- [ ] Trang chủ, catalog, detail, cart, checkout, profile, vendor/management/shipper dashboard không bị tràn ngang hoặc lỗi màu/biểu tượng.
- [ ] Dùng ảnh local/Cloudinary: ảnh sản phẩm public; bằng chứng giao/return chỉ người có quyền xem.
- [ ] `mvnw.cmd test` → `BUILD SUCCESS`; ghi lại ảnh chụp minh chứng, commit, PR và reviewer.
- [ ] Trước khi merge main: TV1 review Customer/Payment, TV2 review Vendor/Stock/Return, TV3 review RBAC/Management/Shipper/WebSocket.

**Ghi chú:** Checklist là các bước cần kiểm tra trực tiếp trên Windows/Java26/SQL Server; đánh dấu `[x]` chỉ sau khi thực sự thực hiện. Bài kiểm thử tự động H2 riêng của Bước 35 không thay thế nghiệm thu SQL Server/VNPay/Email thật.
