# Nhom03 UTETra

## Phạm vi hiện tại của TV1

Nhánh `feature/guest-user` hiện triển khai mục 3.1 Authentication và tài khoản:

- Đăng ký, validation, kiểm tra trùng username/email, BCrypt, tài khoản chờ kích hoạt.
- OTP kích hoạt và reset mật khẩu: hash trong database, hết hạn, dùng một lần, cooldown 60 giây, tối đa 5 mã/giờ, tối đa 5 lần nhập sai.
- Đăng nhập bằng username/email, đăng xuất và Remember Me 7 ngày.
- Quên mật khẩu với thông báo chung cho email tồn tại, không tồn tại và bị giới hạn gửi OTP.
- JWT login API và API đọc tài khoản hiện tại; Swagger với Bearer authentication.

Theo `Nhom03_UTE_ChiTiet.docx`, mục 3.1 được triển khai thành hai commit riêng:

1. `feat(auth): implement registration and email OTP`: đăng ký, OTP kích hoạt và đăng nhập nền. Bước 1 được kiểm thử và merge vào `main` trước khi bắt đầu bước 2.
2. `feat(auth): implement forgot password and JWT login API`: quên/reset mật khẩu, JWT/API login/me và Swagger. Commit bước 2 bắt đầu từ mốc `main` đã có bước 1.

Mỗi bước được đưa vào `main` bằng merge commit để giữ lịch sử. Bản cũ trước khi dựng lại được lưu tại nhánh `archive/tv1-auth-before-split-20261008`. Sau bước 2, TV3 tiếp tục bước 3 RBAC.
Các mục 3.2–3.6 phát triển theo đúng dependency của kế hoạch: catalog cần Product/Shop của TV2; cart/checkout/order cần các module pricing, voucher và vận chuyển. Không đưa code các module đó từ ZIP vào commit Authentication. Authorization cuối cùng cho Vendor/Manager/Admin/Shipper do TV3 tích hợp; `SecurityConfig` hiện chỉ cấu hình authentication nền cho web và API.

## Chạy project

Yêu cầu JDK 26, Spring Boot 4.1.1 theo bản code tham khảo, Maven Wrapper và SQL Server. Database mặc định là `UTETraDB` trên `localhost:1433`; tạo database trước khi chạy, Hibernate tạo/cập nhật bảng.

Đặt các biến môi trường sau trong cấu hình Run của IDE hoặc terminal:

| Biến | Ý nghĩa |
| --- | --- |
| `DB_URL` | JDBC URL nếu khác database mặc định |
| `DB_USERNAME`, `DB_PASSWORD` | Tài khoản SQL Server |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Gmail và App Password để gửi OTP |
| `MAIL_FROM` | Địa chỉ gửi; để trống sẽ dùng `MAIL_USERNAME` |
| `JWT_SECRET` | Khóa ngẫu nhiên bí mật tối thiểu 32 ký tự |
| `REMEMBER_ME_KEY` | Khóa ngẫu nhiên bí mật cho Remember Me |

Không commit giá trị secret thật. Khi không cấu hình mail, chế độ hiện tại ghi OTP DEV vào log để thử cục bộ; demo gửi email thật phải cấu hình Gmail App Password.

Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
bash mvnw test
bash mvnw spring-boot:run
```

Mở `http://localhost:8090/register` hoặc `/login`. Trang chủ/catalog sẽ được TV1 bổ sung khi dependency tương ứng đã merge.

## Demo JWT

1. Đăng ký và kích hoạt email bằng OTP.
2. Mở `http://localhost:8090/swagger-ui/index.html`.
3. Gọi `POST /api/auth/login` với JSON `username` và `password`; username có thể là tên đăng nhập hoặc email.
4. Lấy trường `token`, chọn **Authorize**, nhập token rồi gọi `GET /api/me`.

API dùng Bearer JWT và không dùng session/Remember Me của web. `/api/me` trả `id`, `username`, `email`, `role` của tài khoản được xác thực. Token sai, hết hạn, hoặc tài khoản đã bị khóa/vô hiệu hóa không được truy cập. Chưa có API cart/favorite/order ở giai đoạn này.

## Kiểm thử

`mvnw test` dùng H2 và các secret chỉ dành cho test trong profile `test`; không cần SQL Server hoặc gửi email thật. GitHub Actions chạy bộ test trên JDK 26 cho nhánh TV1 và pull request vào `main`, đồng thời lưu báo cáo Surefire.

Các test bao phủ registration/login/logout, BCrypt, trạng thái tài khoản, JWT, Swagger, CSRF trên form web, Remember Me, OTP rate limit, số lần nhập sai được lưu sau khi transaction kết thúc, OTP hết hạn/dùng một lần và forgot-password privacy. Hãy xem kết quả Actions trước khi merge; việc có test trong source chưa chứng minh test đã chạy thành công.
