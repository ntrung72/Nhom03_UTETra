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

### 1. Chuẩn bị môi trường

- Cài **JDK 25** và **SQL Server**. Có thể dùng **SQL Server Management Studio (SSMS)** để quản lý database.
- Project có sẵn **Maven Wrapper** (`mvnw.cmd`), không cần cài Maven riêng.
- Kiểm tra Java đã được cấu hình bằng lệnh `java -version`. Đảm bảo máy có thể kết nối đến SQL Server bằng tài khoản SQL Server được phép truy cập database.

### 2. Tạo cơ sở dữ liệu UTETraDB

Mở **SSMS**, kết nối đến SQL Server, nhấn **New Query**, rồi chạy lệnh sau (**Execute** hoặc **F5**):

```sql
CREATE DATABASE UTETraDB;
GO
```

Nếu database `UTETraDB` đã tồn tại thì **không chạy lại lệnh tạo database**. Trong môi trường local, ứng dụng sử dụng `spring.jpa.hibernate.ddl-auto=update`, nên Hibernate sẽ tạo hoặc cập nhật các bảng dựa trên entity khi ứng dụng kết nối và khởi động thành công. Lệnh trên **không nhập dữ liệu cũ hoặc dữ liệu từ Azure**.

> **Lưu ý:** Project không còn thư mục `database/` và không yêu cầu chạy file `database/create_utetra_database.sql`.

### 3. Cấu hình SQL Server và khóa bảo mật

Các biến môi trường cần thiết để chạy website local gồm `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` và `REMEMBER_ME_KEY`.

- `JWT_SECRET` phải có **ít nhất 32 ký tự**.
- `REMEMBER_ME_KEY` phải **khác rỗng**. Nên dùng một khóa bí mật ngẫu nhiên riêng, đủ dài.
- Thay các giá trị bắt đầu bằng `THAY_...` trong ví dụ bằng thông tin thật **trước khi chạy**. Không commit mật khẩu, khóa API hoặc khóa bảo mật lên GitHub.

**Cách A — Command Prompt (CMD) trên Windows:** Mở CMD tại thư mục gốc project, sau đó chạy:

```cmd
set "DB_URL=jdbc:sqlserver://localhost:1433;databaseName=UTETraDB;encrypt=true;trustServerCertificate=true"
set "DB_USERNAME=sa"
set "DB_PASSWORD=THAY_MAT_KHAU_SQL_SERVER"
set "JWT_SECRET=THAY_BANG_CHUOI_BI_MAT_NGAU_NHIEN_IT_NHAT_32_KY_TU"
set "REMEMBER_ME_KEY=THAY_BANG_CHUOI_BI_MAT_RIENG_CHO_REMEMBER_ME"
.\mvnw.cmd spring-boot:run
```

**Cách B — PowerShell trên Windows:** Mở PowerShell tại thư mục gốc project, sau đó chạy:

```powershell
$env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=UTETraDB;encrypt=true;trustServerCertificate=true"
$env:DB_USERNAME = "sa"
$env:DB_PASSWORD = "THAY_MAT_KHAU_SQL_SERVER"
$env:JWT_SECRET = "THAY_BANG_CHUOI_BI_MAT_NGAU_NHIEN_IT_NHAT_32_KY_TU"
$env:REMEMBER_ME_KEY = "THAY_BANG_CHUOI_BI_MAT_RIENG_CHO_REMEMBER_ME"
.\mvnw.cmd spring-boot:run
```

**Lưu ý khi cấu hình:**

- Ví dụ sử dụng SQL Server tại `localhost:1433` với tài khoản `sa`. Nếu máy dùng instance, cổng hoặc tài khoản khác, phải thay `DB_URL`, `DB_USERNAME` và `DB_PASSWORD` tương ứng. SQL Server cần bật phương thức xác thực và kết nối phù hợp.
- Các lệnh `set` chỉ có hiệu lực trong cửa sổ CMD hiện tại. Biến `$env:` chỉ có hiệu lực trong phiên PowerShell hiện tại. **Chạy lệnh khởi động trong chính cửa sổ vừa đặt biến**.
- Nếu chạy bằng nút **Run** của Spring Tool Suite/Eclipse, vào **Run Configurations → Environment** để khai báo các biến này. Biến đặt trong CMD sẽ không tự truyền sang STS đã mở riêng.
- Ứng dụng có hỗ trợ file `application-local.properties` ở **thư mục gốc project** thông qua `spring.config.import`. Tuy nhiên, `.gitignore` hiện chưa bỏ qua file ở đúng đường dẫn gốc này. Nếu dùng file đó để chứa khóa, hãy thêm `/application-local.properties` vào `.gitignore` trước và đảm bảo không commit file lên GitHub. Cách dùng biến môi trường phía trên được khuyến nghị.

### 4. Truy cập website và dữ liệu mẫu

Khi console thông báo ứng dụng khởi động thành công, truy cập **<http://localhost:8090>**.

- Mặc định `SEED_DATA=false`, vì vậy database mới **không tự có dữ liệu demo hoặc tài khoản demo**.
- Nếu cần thử tính năng với dữ liệu mẫu, chỉ đặt `SEED_DATA=true` trên **database thử nghiệm**, rồi khởi động ứng dụng theo hướng dẫn của project. Không bật tùy chọn này trên database đang sử dụng thực tế.
- Các tính năng gửi email OTP, thanh toán QR qua payOS và lưu trữ Cloudinary cần cấu hình thêm thông tin dịch vụ tương ứng ở phần dưới.
- Hai thư mục `uploads/` và `private-uploads/` được loại trừ khỏi Git. Khi tải file mới lên, ứng dụng tự tạo thư mục lưu trữ cần thiết nếu có quyền ghi. Các ảnh hoặc bằng chứng cũ được database tham chiếu bằng đường dẫn local sẽ không hiển thị nếu không có file tương ứng.

## Cấu hình các tính năng ngoài

Ngoài các biến để chạy ứng dụng ở trên, một số tính năng cần cấu hình dịch vụ ngoài. Đặt biến trong môi trường chạy ứng dụng. Trên Azure, dùng **Web App → Settings → Environment variables**. Không đưa khóa bí mật vào source code hoặc README.

| Tính năng | Biến cần cấu hình | Ghi chú |
| --- | --- | --- |
| SQL Server | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Cần cấu hình chính xác cho database local hoặc production. |
| Đăng nhập/API và Remember Me | `JWT_SECRET`, `REMEMBER_ME_KEY` | Cần cấu hình để ứng dụng khởi động. JWT tối thiểu 32 ký tự, Remember Me không được rỗng. |
| Email/OTP | `MAIL_USERNAME`, `MAIL_APP_PASSWORD`, `MAIL_FROM` | `MAIL_APP_PASSWORD` là App Password của Gmail. |
| Chuyển khoản payOS | `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`, `PUBLIC_BASE_URL` | `PUBLIC_BASE_URL` là địa chỉ HTTPS công khai của website. Webhook: `/payments/payos/webhook`. |
| Cloudinary | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Nếu chưa cấu hình, file công khai được lưu trong `uploads/`. |
| Nơi lưu file | `UPLOAD_DIR`, `PRIVATE_UPLOAD_DIR` | `private-uploads/` chứa file cần bảo vệ như bằng chứng giao/trả hàng. Khi triển khai, dùng nơi lưu bền vững. |

Các thiết lập mặc định nằm trong [application.properties](src/main/resources/application.properties). Cấu hình cho Azure/production nằm trong [application-prod.properties](src/main/resources/application-prod.properties). Chạy production cần đặt `SPRING_PROFILES_ACTIVE=prod`; chế độ này yêu cầu database đã có schema phù hợp và dùng `ddl-auto=validate`. Database triển khai phải được chuẩn bị schema phù hợp. Không dùng hướng dẫn tạo database local để thay đổi database Azure đang có dữ liệu.

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
| `uploads/`, `private-uploads/` | Thư mục lưu file khi chạy local. Được bỏ qua bởi Git và có thể được tạo khi tải file lên. |
