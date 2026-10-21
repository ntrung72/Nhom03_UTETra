package vn.iotstar.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import vn.iotstar.entity.DomainEnums.MediaType;

@Service
public class MediaValidationService {

	private static final long MB = 1024L * 1024;

	private static final long MAX_IMAGE_PIXELS = 25_000_000L;

	private static final Map<String, List<String>> EXTENSIONS = Map.of("image/jpeg", List.of("jpg", "jpeg"),
			"image/png", List.of("png"), "image/webp", List.of("webp"), "video/mp4", List.of("mp4"), "video/webm",
			List.of("webm"), "video/quicktime", List.of("mov"));

	public MediaType validate(MultipartFile file, String folder) {
		Policy policy = policy(folder);

		if (file == null || file.isEmpty() || file.getSize() <= 0) {
			throw new IllegalArgumentException("Vui lòng chọn tệp để tải lên.");
		}

		String filename = file.getOriginalFilename();

		if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
				|| filename.contains("\\") || filename.contains(":")
				|| filename.chars().anyMatch(Character::isISOControl)) {

			throw new IllegalArgumentException("Tên tệp tải lên không hợp lệ.");
		}

		String mime = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);

		int dot = filename.lastIndexOf('.');

		String extension = dot <= 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);

		List<String> extensions = EXTENSIONS.get(mime);

		if (extensions == null || !extensions.contains(extension)) {
			throw new IllegalArgumentException(
					"Định dạng tệp không hợp lệ. " + "Ảnh: JPG, PNG, WEBP; video: MP4, WEBM, MOV.");
		}

		MediaType type = mime.startsWith("image/") ? MediaType.IMAGE : MediaType.VIDEO;

		if (type == MediaType.VIDEO && !policy.allowVideo()) {
			throw new IllegalArgumentException("Chức năng này chỉ hỗ trợ ảnh JPG, PNG hoặc WEBP.");
		}

		long limit = (type == MediaType.IMAGE ? policy.imageLimitMb() : policy.videoLimitMb()) * MB;

		if (file.getSize() > limit) {
			throw new IllegalArgumentException("Tệp vượt quá giới hạn " + (limit / MB) + " MB.");
		}

		byte[] content = readContent(file, limit);

		if (type == MediaType.IMAGE) {
			validateImage(content, mime);
		} else {
			validateVideoHeader(content, mime);
		}

		return type;
	}

	public List<MultipartFile> validateFiles(MultipartFile[] uploads, String folder) {

		Policy policy = policy(folder);

		if (uploads == null) {
			return List.of();
		}

		List<MultipartFile> files = Arrays.stream(uploads).filter(file -> file != null && !file.isEmpty()).toList();

		if (files.size() > policy.maxFiles()) {
			throw new IllegalArgumentException("Chỉ được tải lên tối đa " + policy.maxFiles() + " tệp.");
		}

		files.forEach(file -> validate(file, folder));

		return files;
	}

	private byte[] readContent(MultipartFile file, long limit) {
		try (InputStream input = file.getInputStream()) {
			byte[] content = input.readNBytes(Math.toIntExact(limit + 1));

			if (content.length == 0 || content.length > limit || content.length != file.getSize()) {

				throw new IllegalArgumentException("Dung lượng thực tế của tệp không hợp lệ.");
			}

			return content;

		} catch (IOException ex) {
			throw new IllegalArgumentException("Không thể đọc tệp tải lên.", ex);
		}
	}

	private void validateImage(byte[] content, String mime) {
		boolean signatureMatches = switch (mime) {
		case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);

		case "image/png" -> startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

		case "image/webp" -> content.length >= 20 && textAt(content, 0, "RIFF") && textAt(content, 8, "WEBP")
				&& littleEndianInt(content, 4) + 8 == content.length;

		default -> false;
		};

		if (!signatureMatches) {
			throw new IllegalArgumentException("Nội dung ảnh không khớp với định dạng đã khai báo.");
		}

		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {

			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

			if (!readers.hasNext()) {
				throw new IllegalArgumentException("Không thể giải mã ảnh. " + "Với WEBP, cần cấu hình bộ đọc WEBP.");
			}

			ImageReader reader = readers.next();

			try {
				reader.setInput(input, true, true);

				int width = reader.getWidth(0);
				int height = reader.getHeight(0);

				if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {

					throw new IllegalArgumentException(
							"Kích thước ảnh không hợp lệ " + "hoặc vượt quá 25 triệu pixel.");
				}

				BufferedImage image = reader.read(0);

				if (image == null) {
					throw new IllegalArgumentException("Ảnh bị hỏng hoặc không đọc được.");
				}

				try {
					if (image.getWidth() != width || image.getHeight() != height) {

						throw new IllegalArgumentException("Kích thước ảnh giải mã không hợp lệ.");
					}
				} finally {
					image.flush();
				}

			} finally {
				reader.dispose();
			}

		} catch (IllegalArgumentException ex) {
			throw ex;

		} catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("Ảnh bị hỏng hoặc không đọc được.", ex);
		}
	}

	/*
	 * Kiểm tra container/header của video. Không thay thế việc giải mã toàn bộ
	 * video.
	 */
	private void validateVideoHeader(byte[] content, String mime) {
		boolean valid = switch (mime) {
		case "video/mp4" -> isIsoVideo(content, false);

		case "video/quicktime" -> isIsoVideo(content, true);

		case "video/webm" -> isWebm(content);

		default -> false;
		};

		if (!valid) {
			throw new IllegalArgumentException("Nội dung video không khớp định dạng " + "MP4, WEBM hoặc MOV.");
		}
	}

	private boolean isIsoVideo(byte[] data, boolean quickTime) {
		if (data.length < 32 || !textAt(data, 4, "ftyp")) {
			return false;
		}

		long boxSize = bigEndianInt(data, 0);

		if (boxSize < 16 || boxSize > data.length || boxSize % 4 != 0) {
			return false;
		}

		boolean qtBrand = textAt(data, 8, "qt  ");
		boolean mp4Brand = isMp4Brand(data, 8);

		for (int offset = 16; offset + 4 <= boxSize; offset += 4) {
			qtBrand |= textAt(data, offset, "qt  ");
			mp4Brand |= isMp4Brand(data, offset);
		}

		if (quickTime ? !qtBrand : (!mp4Brand || qtBrand)) {
			return false;
		}

		boolean movie = false;
		boolean media = false;

		int offset = (int) boxSize;

		while (offset < data.length) {
			if (data.length - offset < 8) {
				return false;
			}

			long size = bigEndianInt(data, offset);
			int headerSize = 8;

			if (size == 1) {
				if (data.length - offset < 16) {
					return false;
				}

				long high = bigEndianInt(data, offset + 8);
				long low = bigEndianInt(data, offset + 12);

				if (high != 0) {
					return false;
				}

				size = low;
				headerSize = 16;

			} else if (size == 0) {
				size = data.length - offset;
			}

			if (size < headerSize || size > data.length - offset) {
				return false;
			}

			movie |= textAt(data, offset + 4, "moov") && size > headerSize;

			media |= textAt(data, offset + 4, "mdat") && size > headerSize;

			offset += (int) size;
		}

		return movie && media;
	}

	private boolean isMp4Brand(byte[] data, int offset) {
		for (String brand : List.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ",
				"dash")) {

			if (textAt(data, offset, brand)) {
				return true;
			}
		}

		return false;
	}

	private boolean isWebm(byte[] data) {
		if (data.length < 16 || !startsWith(data, 0x1A, 0x45, 0xDF, 0xA3)) {
			return false;
		}

		Vint headerSize = vint(data, 4);

		if (headerSize == null || headerSize.value() > data.length) {
			return false;
		}

		int start = 4 + headerSize.length();
		long endValue = start + headerSize.value();

		if (endValue > data.length - 5) {
			return false;
		}

		int end = (int) endValue;
		boolean webm = false;
		int offset = start;

		while (offset < end) {
			Vint id = vint(data, offset);

			if (id == null || id.length() > 4 || offset + id.length() > end) {
				return false;
			}

			boolean docType = id.length() == 2 && (data[offset] & 0xFF) == 0x42 && (data[offset + 1] & 0xFF) == 0x82;

			offset += id.length();

			Vint size = vint(data, offset);

			if (size == null || offset + size.length() > end) {
				return false;
			}

			offset += size.length();

			if (size.value() > end - offset) {
				return false;
			}

			if (docType) {
				if (webm || size.value() != 4 || !textAt(data, offset, "webm")) {
					return false;
				}

				webm = true;
			}

			offset += (int) size.value();
		}

		// Segment ID của WebM: 18 53 80 67.
		if (!webm || data[end] != 0x18 || data[end + 1] != 0x53 || (data[end + 2] & 0xFF) != 0x80
				|| data[end + 3] != 0x67) {
			return false;
		}

		Vint segmentSize = vint(data, end + 4);

		if (segmentSize == null) {
			return false;
		}

		int body = end + 4 + segmentSize.length();

		long unknownSize = (1L << (7 * segmentSize.length())) - 1;

		return body < data.length && (segmentSize.value() == unknownSize || segmentSize.value() == data.length - body);
	}

	private Vint vint(byte[] data, int offset) {
		if (offset < 0 || offset >= data.length || data[offset] == 0) {
			return null;
		}

		int first = data[offset] & 0xFF;
		int mask = 0x80;
		int length = 1;

		while ((first & mask) == 0) {
			mask >>= 1;
			length++;
		}

		if (length > 8 || offset + length > data.length) {
			return null;
		}

		long value = first & (mask - 1);

		for (int i = 1; i < length; i++) {
			value = (value << 8) | (data[offset + i] & 0xFF);
		}

		return new Vint(value, length);
	}

	private boolean startsWith(byte[] data, int... signature) {
		if (data.length < signature.length) {
			return false;
		}

		for (int i = 0; i < signature.length; i++) {
			if ((data[i] & 0xFF) != signature[i]) {
				return false;
			}
		}

		return true;
	}

	private boolean textAt(byte[] data, int offset, String expected) {

		byte[] bytes = expected.getBytes(StandardCharsets.US_ASCII);

		if (offset < 0 || offset > data.length - bytes.length) {
			return false;
		}

		for (int i = 0; i < bytes.length; i++) {
			if (data[offset + i] != bytes[i]) {
				return false;
			}
		}

		return true;
	}

	private long bigEndianInt(byte[] data, int offset) {
		return ((data[offset] & 0xFFL) << 24) | ((data[offset + 1] & 0xFFL) << 16) | ((data[offset + 2] & 0xFFL) << 8)
				| (data[offset + 3] & 0xFFL);
	}

	private long littleEndianInt(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private Policy policy(String folder) {
		if (folder == null) {
			throw new IllegalArgumentException("Thư mục tải lên không hợp lệ.");
		}

		return switch (folder) {
		case "avatars" -> new Policy(5, 0, false, 1);

		case "delivery-proofs" -> new Policy(10, 0, false, 1);

		case "products" -> new Policy(10, 0, false, 8);

		case "shops", "categories" -> new Policy(10, 0, false, 1);

		case "reviews", "returns" -> new Policy(10, 25, true, 5);

		default -> throw new IllegalArgumentException("Thư mục tải lên không hợp lệ.");
		};
	}

	private record Policy(int imageLimitMb, int videoLimitMb, boolean allowVideo, int maxFiles) {
	}

	private record Vint(long value, int length) {
	}
}