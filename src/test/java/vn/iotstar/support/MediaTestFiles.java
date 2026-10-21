package vn.iotstar.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.springframework.mock.web.MockMultipartFile;

public final class MediaTestFiles {

	private static final byte[] PNG = encode("png");
	private static final byte[] JPEG = encode("jpeg");

	// Ảnh WEBP lossless thật, kích thước 2 x 2 pixel.
	private static final byte[] WEBP = Base64.getDecoder()
			.decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAUAAAAfQw6pUq/+BiOh/AAA=");

	private MediaTestFiles() {
	}

	public static MockMultipartFile image(String filename) {
		return image("file", filename);
	}

	public static MockMultipartFile image(String fieldName, String filename) {

		if (filename == null || filename.lastIndexOf('.') < 0) {

			throw new IllegalArgumentException("Tên ảnh mẫu phải có phần mở rộng.");
		}

		String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);

		return switch (extension) {
		case "png" -> new MockMultipartFile(fieldName, filename, "image/png", pngBytes());

		case "jpg", "jpeg" -> new MockMultipartFile(fieldName, filename, "image/jpeg", jpegBytes());

		case "webp" -> new MockMultipartFile(fieldName, filename, "image/webp", webpBytes());

		default -> throw new IllegalArgumentException("Ảnh mẫu chỉ hỗ trợ PNG, JPG và WEBP.");
		};
	}

	public static byte[] pngBytes() {
		return PNG.clone();
	}

	public static byte[] jpegBytes() {
		return JPEG.clone();
	}

	public static byte[] webpBytes() {
		return WEBP.clone();
	}

	/*
	 * Tạo PNG có dung lượng chính xác để kiểm tra giới hạn upload. Thêm một
	 * ancillary chunk hợp lệ trước IEND, có CRC đầy đủ.
	 */
	public static byte[] pngBytesWithSize(int totalBytes) {
		if (totalBytes == PNG.length) {
			return pngBytes();
		}

		if (totalBytes < PNG.length + 12 || totalBytes > 26 * 1024 * 1024) {

			throw new IllegalArgumentException(
					"Dung lượng PNG mẫu phải ít nhất " + (PNG.length + 12) + " byte và không vượt quá 26 MB.");
		}

		int payloadLength = totalBytes - PNG.length - 12;

		byte[] chunkType = "npAd".getBytes(StandardCharsets.US_ASCII);

		byte[] payload = new byte[payloadLength];

		CRC32 crc = new CRC32();
		crc.update(chunkType);
		crc.update(payload);

		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(totalBytes);

				DataOutputStream output = new DataOutputStream(bytes)) {
			int iendOffset = PNG.length - 12;

			output.write(PNG, 0, iendOffset);
			output.writeInt(payloadLength);
			output.write(chunkType);
			output.write(payload);
			output.writeInt((int) crc.getValue());
			output.write(PNG, iendOffset, 12);
			output.flush();

			return bytes.toByteArray();

		} catch (IOException ex) {
			throw new IllegalStateException("Không thể tạo PNG mẫu theo dung lượng.", ex);
		}
	}

	private static byte[] encode(String format) {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);

		try {
			image.setRGB(0, 0, 0x2A875A);
			image.setRGB(1, 0, 0xD9A441);
			image.setRGB(0, 1, 0x486CA8);
			image.setRGB(1, 1, 0xF4E7CF);

			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				if (!ImageIO.write(image, format, output)) {
					throw new IllegalStateException("Không có bộ ghi ảnh mẫu cho định dạng " + format);
				}

				return output.toByteArray();
			}

		} catch (IOException ex) {
			throw new IllegalStateException("Không thể tạo ảnh mẫu cho test.", ex);

		} finally {
			image.flush();
		}
	}
}