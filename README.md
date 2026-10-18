# Nhóm 03 – UTETra

Website bán trà sữa đa cửa hàng (Spring Boot, Thymeleaf, Spring Security, SQL Server). Source này duy trì lịch sử Git và phân chia nghiệp vụ Guest/User, Vendor, Manager, Admin, Shipper của nhóm.

## Yêu cầu

- Java **26** theo `pom.xml`, Maven qua `mvnw.cmd` (Windows).
- SQL Server có database `UTETraDB` hoặc cấu hình `DB_URL` tới database khác.
- SMTP Email/App Password để thử OTP; cấu hình VNPay Sandbox để thử thanh toán thật qua sandbox.
- Không đưa mật khẩu DB, JWT/Remember-Me key, API key, chữ ký VNPay và dữ liệu upload vào Git.

## Biến môi trường (CMD Windows)

Thiết lập trong cửa sổ CMD chạy ứng dụng (các giá trị cần lấy từ môi trường của chính bạn):

```cmd
set DB_URL=jdbc:sqlserver://localhost:1433;databaseName=UTETraDB;encrypt=true;trustServerCertificate=true
set DB_USERNAME=sa
set DB_PASSWORD=<mat-khau-SQL-Server>
set JWT_SECRET=<secret-it-nhat-32-ky-tu>
set REMEMBER_ME_KEY=<remember-me-key-rieng>
set MAIL_USERNAME=<tai-khoan-email>
set MAIL_PASSWORD=<email-app-password>
set MAIL_FROM=<email-gui>
set WS_ALLOWED_ORIGINS=http://localhost:8090,http://127.0.0.1:8090
```

Các biến không bắt buộc khi thử chức năng không tích hợp ngoại vi: `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`.

### Demo seed **chỉ khi chủ động bật**

Trên **database trống**, trước khi chạy lần đầu:

```cmd
set SEED_DATA=true
mvnw.cmd spring-boot:run
```

Tài khoản minh họa nằm trong `DataInitializer.java`: `admin`, `manager`, `vendor`, `vendor2`, `shipper`, `user`; các mật khẩu demo tương ứng là `Admin@123`, `Manager@123`, `Vendor@123` (cả hai Vendor), `Shipper@123`, `User@123`. **Chỉ sử dụng trên database demo nội bộ**. Seed có 2 shop, 30 sản phẩm, 4 danh mục, carrier, voucher, promotion và ảnh tĩnh tham chiếu từ UTETra(7).

Mặc định `SEED_DATA=false`: **không tạo tài khoản/sản phẩm demo**. Khi database đã có người dùng/shop/sản phẩm/danh mục, seed sẽ không ghi đè hoặc thêm bản demo. Nên đặt lại `set SEED_DATA=false` sau khi tạo demo lần đầu.

## Migrations/backfill cho database cũ

Các component sau chạy khi khởi động, độc lập với `SEED_DATA`:

- `CartOptionsSchemaMigration`: bỏ ràng buộc unique kiểu cũ của cart (`cart_id,product_id`), giữ unique theo `option_key` để cùng sản phẩm có nhiều cấu hình.
- `OrderStatusSchemaMigration`: cập nhật check constraint enum cũ **trên SQL Server** cho luồng Vendor/Shipper, COD/VNPay và Return.
- `VnPaySchemaMigration`: giữ logic tương thích VNPay đã có trong nhánh nhóm.
- `CommissionSnapshotBackfill`: giữ cách tính và bảo toàn `commissionRate` lịch sử của nhóm.
- `VoucherUsageBackfill`: bổ sung thông tin voucher/giảm giá cho dữ liệu cũ và tạo tối đa một usage theo `checkoutReference`. Vì nhóm dùng `Long orderId`, migration được điều chỉnh đúng entity hiện tại, **không tạo lại Order/Voucher**.

**Trước khi chạy trên SQL Server có dữ liệu thật:** sao lưu toàn bộ database, thử migration trên bản sao, xác minh trạng thái đơn hàng/Voucher/Commission và giữ bản sao lưu để rollback. Database mới nên được thử riêng. Lần tích hợp này kiểm tra trực tiếp với H2; **chưa chạy SQL Server thực trên máy của người dùng**.

## Build và chạy

```cmd
mvnw.cmd test
mvnw.cmd spring-boot:run
```

Web: `http://localhost:8090`. Kiểm tra riêng Bước 35:

```cmd
mvnw.cmd -Dtest=Step35IntegrationTests,Step35NoSeedTests test
```

Xem [`docs/BUOC35_SMOKE_TEST.md`](docs/BUOC35_SMOKE_TEST.md) để nghiệm thu từ Guest đến Shipper, WebSocket, VNPay, OTP và giao diện laptop. Việc nghiệm thu thực tế ngoài môi trường H2 cần TV1/TV2 cùng review vì tác động liên role.

## Quy tắc tích hợp

Không chép đè `.git` từ ZIP vào project đang làm việc. Chỉ đưa những file thay đổi thật sự của Bước 35 qua Git; giữ logic Checkout/OTP của TV1, Vendor/Inventory/Return của TV2 và các bước TV3 đã làm.
