# Nhom03 UTETra

## Bước 1 của TV1

Commit `feat(auth): implement registration and email OTP` triển khai riêng bước 1 trong `Nhom03_UTE_ChiTiet.docx`:

- Đăng ký, validation và kiểm tra trùng username/email; mật khẩu BCrypt, tài khoản mặc định USER và chờ kích hoạt.
- OTP kích hoạt email: hash trong database, hết hạn sau 5 phút, dùng một lần, cooldown 60 giây, tối đa 5 mã/giờ và khóa OTP sau 5 lần nhập sai.
- Đăng nhập bằng username/email, đăng xuất, Remember Me 7 ngày và cấu hình authentication nền.

Luồng quên mật khẩu, JWT và Swagger sẽ được thêm riêng ở bước 2 sau khi bước 1 đã được merge vào `main`. RBAC và các module nghiệp vụ phát triển theo các bước tiếp theo của nhóm.

## Chạy project

Yêu cầu JDK 26, Spring Boot 4.1.1 và SQL Server. Tạo database `UTETraDB` trên `localhost:1433` hoặc cấu hình `DB_URL`; Hibernate tạo/cập nhật các bảng.

| Biến môi trường | Ý nghĩa |
| --- | --- |
| `DB_URL` | JDBC URL nếu khác database mặc định |
| `DB_USERNAME`, `DB_PASSWORD` | Tài khoản SQL Server |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Gmail và App Password để gửi OTP |
| `MAIL_FROM` | Địa chỉ gửi; để trống sẽ dùng `MAIL_USERNAME` |
| `REMEMBER_ME_KEY` | Khóa ngẫu nhiên bí mật cho Remember Me |

Không commit secret thật. Khi không cấu hình mail, OTP DEV được ghi vào log để thử cục bộ; demo gửi email thật phải cấu hình Gmail App Password.

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

Mở `http://localhost:8090/register`, nhận OTP và kích hoạt trước khi đăng nhập tại `/login`.

## Kiểm thử

Test sử dụng H2 và secret chỉ dành cho test trong profile `test`. GitHub Actions chạy trên JDK 26 và lưu báo cáo Surefire. Phạm vi test ở bước này gồm validation, BCrypt, đăng nhập/đăng xuất, trạng thái tài khoản, Remember Me, CSRF, OTP rate limit và OTP persistence. Demo Gmail thật và SQL Server cần kiểm tra bằng cấu hình triển khai của nhóm.
