# UTETra

UTETra là website bán trà sữa cho nhiều cửa hàng. Khách chọn món và đặt hàng; cửa hàng chuẩn bị món; quản trị viên điều phối giao hàng; shipper giao cho khách. Website cũng có quản lý khuyến mãi, đánh giá, điểm thưởng và trả hàng/hoàn tiền.

## Công nghệ

- **Ứng dụng:** Java 25, Spring Boot, Spring Security, Thymeleaf, WebSocket.
- **Dữ liệu:** SQL Server, Spring Data JPA. Bộ kiểm thử dùng H2.
- **Tích hợp:** payOS cho chuyển khoản QR, Gmail cho email/OTP, Cloudinary cho ảnh và video, Swagger UI cho tài liệu API.

## Ai làm gì trên website?

| Vai trò | Chức năng chính |
| --- | --- |
| Khách chưa đăng nhập | Xem, tìm kiếm sản phẩm và cửa hàng. |
| Khách hàng (`USER`) | Quản lý hồ sơ, địa chỉ, giỏ hàng; đặt và theo dõi đơn; lưu công thức, theo dõi cửa hàng, đánh giá, xem điểm thưởng và yêu cầu trả hàng. |
| Chủ cửa hàng (`VENDOR`) | Quản lý cửa hàng, sản phẩm, kho và khuyến mãi; xác nhận đơn, chuẩn bị món, đánh dấu sẵn sàng lấy hàng; xử lý yêu cầu trả hàng. |
| Shipper (`SHIPPER`) | Chấp nhận hoặc từ chối đơn được phân công; cập nhật lấy hàng, giao hàng và bằng chứng giao hàng. |
| Quản lý (`MANAGER`) | Xem và quản lý dữ liệu của các cửa hàng được Admin phân công; theo dõi đơn và điều phối shipper. |
| Quản trị viên (`ADMIN`) | Quản lý toàn hệ thống, phân công Manager, quản lý voucher và thanh toán, điều phối đơn, xem nhật ký thao tác và xử lý hoàn tiền. |

## Luồng đơn hàng

1. Khách chọn sản phẩm, size, đường, đá, topping và số lượng; sau đó đặt hàng từ giỏ.
2. Khách chọn **COD** hoặc **chuyển khoản QR**. Với QR, payOS tạo link riêng cho đơn. Hệ thống xác nhận thanh toán từ thông báo hợp lệ hoặc kết quả đối chiếu với payOS; chỉ quay về website không đồng nghĩa đã trả tiền.
3. Vendor xác nhận đơn, chuẩn bị món rồi đánh dấu **sẵn sàng lấy hàng**. Admin/Manager theo dõi và phân công shipper; shipper chấp nhận, lấy hàng rồi giao cho khách.
4. Sau khi nhận hàng, khách có thể đánh giá và gửi yêu cầu trả hàng. Nếu yêu cầu được duyệt, Admin tự chuyển khoản hoàn tiền vào tài khoản khách đã chọn rồi ghi mã giao dịch để xác nhận hoàn tiền.

VNPay và MoMo không còn là lựa chọn khi tạo đơn mới. Dữ liệu đơn cũ dùng các phương thức đó vẫn được giữ để xem lịch sử.

## Chạy trên máy cá nhân

### 1. Chuẩn bị

- Cài **JDK 25** và **SQL Server**. Project có sẵn Maven Wrapper, không cần cài Maven riêng.
- Tạo database `UTETraDB` bằng cách mở [database/create_utetra_database.sql](database/create_utetra_database.sql) trong SSMS và chạy toàn bộ script. Script tạo database; ở chế độ chạy local, Hibernate tạo/cập nhật các bảng khi ứng dụng khởi động.

### 2. Cấu hình kết nối

Ứng dụng mặc định kết nối `localhost:1433`, database `UTETraDB`, tài khoản SQL Server `sa`. Đặt mật khẩu và các giá trị riêng bằng biến môi trường, hoặc trong `application-local.properties` ở **thư mục gốc project**. File này được Git bỏ qua.

Ví dụ trong PowerShell, thay giá trị bằng thông tin SQL Server của bạn:

```powershell
$env:DB_URL="jdbc:sqlserver://localhost:1433;databaseName=UTETraDB;encrypt=true;trustServerCertificate=true"
$env:DB_USERNAME="sa"
$env:DB_PASSWORD="mat-khau-cua-ban"
```

Nếu SQL Server chạy bằng instance hoặc cổng khác, sửa `DB_URL` cho đúng. Không đưa mật khẩu hoặc khóa API vào Git.

### 3. Chạy website

```powershell
.\mvnw.cmd spring-boot:run
```

Mở <http://localhost:8090>. Mặc định `SEED_DATA=false`, nên ứng dụng không tự thêm tài khoản và dữ liệu mẫu. Chỉ bật `SEED_DATA=true` khi muốn nạp dữ liệu mẫu vào **database thử nghiệm**.

## Cấu hình các tính năng ngoài

Các biến sau chỉ cần đặt khi dùng tính năng tương ứng. Đặt chúng trong môi trường chạy ứng dụng; trên Azure, dùng **Web App → Settings → Environment variables**.

| Tính năng | Biến cần cấu hình | Ghi chú |
| --- | --- | --- |
| SQL Server | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Bắt buộc khi chạy production. |
| Đăng nhập/API | `JWT_SECRET`, `REMEMBER_ME_KEY` | Dùng chuỗi bí mật riêng, đủ dài. |
| Email/OTP | `MAIL_USERNAME`, `MAIL_APP_PASSWORD`, `MAIL_FROM` | `MAIL_APP_PASSWORD` là App Password của Gmail. |
| Chuyển khoản payOS | `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`, `PUBLIC_BASE_URL` | `PUBLIC_BASE_URL` là địa chỉ HTTPS công khai của website. Webhook: `/payments/payos/webhook`. |
| Cloudinary | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Nếu chưa cấu hình, file công khai được lưu trong `uploads/`. |
| Nơi lưu file | `UPLOAD_DIR`, `PRIVATE_UPLOAD_DIR` | `private-uploads/` chứa file cần bảo vệ như bằng chứng giao/trả hàng. Khi triển khai, dùng nơi lưu bền vững. |

Các thiết lập mặc định nằm trong [application.properties](src/main/resources/application.properties). Cấu hình cho Azure/production nằm trong [application-prod.properties](src/main/resources/application-prod.properties). Chạy production cần đặt `SPRING_PROFILES_ACTIVE=prod`; chế độ này yêu cầu database đã có schema phù hợp và dùng `ddl-auto=validate`. Đừng chạy script tạo database local lên database đang có dữ liệu trên Azure.

## Kiểm thử và đóng gói

Chạy từ thư mục gốc project:

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

File sau khi đóng gói là `target/UTETra.jar`. Để chạy file JAR trên máy cá nhân:

```powershell
java -jar target\UTETra.jar
```

Swagger UI: `/swagger-ui/index.html`; tài liệu OpenAPI: `/v3/api-docs`. API đăng nhập nhận token tại `POST /api/auth/login`. Các chức năng REST khác cần quyền phù hợp.

## Thư mục chính

| Thư mục/file | Nội dung |
| --- | --- |
| `src/main/java/vn/iotstar/` | Controller, service, entity, repository và cấu hình. |
| `src/main/resources/templates/` | Giao diện Thymeleaf. |
| `src/main/resources/static/` | CSS, JavaScript và ảnh có sẵn. |
| `src/test/` | Kiểm thử. |
| `database/` | Script SQL dùng khi tạo database local và các tác vụ dữ liệu riêng. |
| `uploads/`, `private-uploads/` | File được tải lên khi dùng lưu trữ local. |
