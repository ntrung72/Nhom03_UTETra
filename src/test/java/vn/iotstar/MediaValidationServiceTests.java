package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.service.MediaValidationService;
import vn.iotstar.support.MediaTestFiles;

class MediaValidationServiceTests {

	private static final int MB = 1024 * 1024;

	private final MediaValidationService validation = new MediaValidationService();

	// Video 16 x 16 pixel, một frame, được mã hóa bằng FFmpeg.
	private static final Map<String, byte[]> VIDEOS = Map.of("mp4", Base64.getDecoder()
			.decode("AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAAAhmcmVlAAAAHG1kYXQAAAGzABAHAAABthYFGFBtgtAoPwAAAwttb292"
					+ "AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAA"
					+ "AAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACWnRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAA"
					+ "AAAD6AAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRz"
					+ "AAAAHGVsc3QAAAAAAAAAAQAAA+gAAAAAAAEAAAAAAdJtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAt"
					+ "aGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAF9bWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAA"
					+ "JGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABPXN0YmwAAADZc3RzZAAAAAAAAAABAAAAyW1wNHYAAAAAAAAA"
					+ "AQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgAAABIAAAAAAAAAAEKTGF2YyBtcGVnNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY//8A"
					+ "AABPZXNkcwAAAAADgICAPgABAASAgIAwIBEAAAAAAw1AAAAAoAWAgIAeAAABsAEAAAG1iRMAAAEAAAABIADEjYgADQCEAhRj"
					+ "BoCAgAECAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAADDUAAAACgAAAAGHN0dHMAAAAAAAAAAQAAAAEAAEAAAAAAHHN0"
					+ "c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAABQAAAABAAAAFHN0Y28AAAAAAAAAAQAAACwAAAA9dWR0YQAA"
					+ "ADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAAhpbHN0"),
			"mov",
			Base64.getDecoder().decode(
					"AAAAFGZ0eXBxdCAgAAACAHF0ICAAAAAId2lkZQAAABxtZGF0AAABswAQBwAAAbYWBRhQbYLQKD8AAALmbW9vdgAAAGxtdmhk"
							+ "AAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAA"
							+ "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAnJ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAA"
							+ "AAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0"
							+ "AAAAAAAAAAEAAAPoAAAAAAABAAAAAAHqbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQAB//wAAAAAALWhkbHIAAAAA"
							+ "bWhscnZpZGUAAAAAAAAAAAAAAAAMVmlkZW9IYW5kbGVyAAABlW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACxoZGxyAAAA"
							+ "AGRobHJ1cmwgAAAAAAAAAAAAAAAAC0RhdGFIYW5kbGVyAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAB"
							+ "KXN0YmwAAADFc3RzZAAAAAAAAAABAAAAtW1wNHYAAAAAAAAAAQAAAABGRk1QAAACAAAAAgAAEAAQAEgAAABIAAAAAAAAAAEK"
							+ "TGF2YyBtcGVnNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY//8AAABPZXNkcwAAAAADgICAPgABAASAgIAwIBEAAAAAAw1AAAAA"
							+ "oAWAgIAeAAABsAEAAAG1iRMAAAEAAAABIADEjYgADQCEAhRjBoCAgAECAAAAEHBhc3AAAAABAAAAAQAAABhzdHRzAAAAAAAA"
							+ "AAEAAAABAABAAAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAAAUAAAAAQAAABRzdGNvAAAAAAAA"
							+ "AAEAAAAk"),
			"webm",
			Base64.getDecoder().decode(
					"GkXfo59ChoEBQveBAULygQRC84EIQoKEd2VibUKHgQJChYECGFOAZwEAAAAAAAG9EU2bdLpNu4tTq4QVSalmU6yBoU27i1Or"
							+ "hBZUrmtTrIHGTbuMU6uEElTDZ1OsggEMTbuMU6uEHFO7a1OsggGn7AEAAAAAAABZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
							+ "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAVSalmoCrX"
							+ "sYMPQkBNgIRMYXZmV0GETGF2ZkSJiECPQAAAAAAAFlSua8GuAQAAAAAAADjXgQFzxYgAAAAAAAAAAZyBACK1nIN1bmSIgQCG"
							+ "hVZfVlA4g4EBI+ODhDuaygDgibCBELqBEJqBAhJUw2fQc3PNY8CLY8WIAAAAAAAAAAFnyJhFo4dFTkNPREVSRIeLTGF2YyBs"
							+ "aWJ2cHhnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAxLjAwMDAwMDAwMAAfQ7Z1weeBAKO8gQAAgPACAJ0BKhAAEAAARwiFhYiZ"
							+ "hIgCAgJ1qgP4A/oCDU22AP79bvP/4cfUYUH/hyn/0lS4iqr/0gAAHFO7a5G7j7OBALeK94EB8YIBYfCBAw=="));

	@Test
	void acceptsSupportedImageFormats() {
		for (String extension : List.of("jpg", "jpeg", "png", "webp")) {
			assertEquals(MediaType.IMAGE, validation.validate(MediaTestFiles.image("photo." + extension), "products"));
		}
	}

	@Test
	void normalizesMimeWhitespaceAndFilenameCase() {
		assertEquals(MediaType.IMAGE,
				validation.validate(file("PHOTO.JPEG", " IMAGE/JPEG ", MediaTestFiles.jpegBytes()), "products"));

		assertEquals(MediaType.IMAGE,
				validation.validate(file("PHOTO.PNG", "IMAGE/PNG", MediaTestFiles.pngBytes()), "shops"));

		assertEquals(MediaType.VIDEO,
				validation.validate(file("CLIP.MOV", " VIDEO/QUICKTIME ", videoBytes("mov")), "returns"));
	}

	@Test
	void acceptsSupportedVideosForReviewsAndReturns() {
		for (String folder : List.of("reviews", "returns")) {
			for (String extension : List.of("mp4", "mov", "webm")) {
				assertEquals(MediaType.VIDEO, validation.validate(video(extension), folder));
			}
		}
	}

	@Test
	void rejectsVideosForEveryImageOnlyFolder() {
		for (String folder : List.of("avatars", "delivery-proofs", "products", "shops", "categories")) {

			assertThrows(IllegalArgumentException.class, () -> validation.validate(video("mp4"), folder));
		}
	}

	@Test
	void rejectsMissingOrEmptySingleUpload() {
		assertThrows(IllegalArgumentException.class, () -> validation.validate(null, "products"));

		assertThrows(IllegalArgumentException.class,
				() -> validation.validate(file("empty.png", "image/png", new byte[0]), "products"));
	}

	@Test
	void rejectsUnknownMimesAndMismatchedExtensions() {
		byte[] png = MediaTestFiles.pngBytes();

		for (MultipartFile upload : List.of(file("photo.jpg", "image/png", png),
				file("photo.png.exe", "image/png", png), file("photo.svg", "image/svg+xml", png),
				file("photo.png", "application/octet-stream", png), file("page.html", "text/html", png),
				file("clip.mp4", "image/jpeg", png), file("photo.png", null, png))) {

			assertThrows(IllegalArgumentException.class, () -> validation.validate(upload, "returns"));
		}
	}

	@Test
	void rejectsMissingFilenameOrExtension() {
		for (String filename : new String[] { null, "", "photo", "photo.", ".png" }) {

			assertThrows(IllegalArgumentException.class,
					() -> validation.validate(file(filename, "image/png", MediaTestFiles.pngBytes()), "products"));
		}
	}

	@Test
	void imageSizeLimitIncludesExactBoundaryButRejectsOneByteMore() {
		Map<String, Integer> limits = Map.of("avatars", 5, "delivery-proofs", 10, "products", 10, "shops", 10,
				"categories", 10, "reviews", 10, "returns", 10);

		Map<Integer, byte[]> exact = Map.of(5, MediaTestFiles.pngBytesWithSize(5 * MB), 10,
				MediaTestFiles.pngBytesWithSize(10 * MB));

		Map<Integer, byte[]> oversized = Map.of(5, MediaTestFiles.pngBytesWithSize(5 * MB + 1), 10,
				MediaTestFiles.pngBytesWithSize(10 * MB + 1));

		limits.forEach((folder, megabytes) -> {
			assertEquals(MediaType.IMAGE,
					validation.validate(file("photo.png", "image/png", exact.get(megabytes)), folder));

			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> validation.validate(file("photo.png", "image/png", oversized.get(megabytes)), folder));

			assertTrue(error.getMessage().contains(megabytes + " MB"));
		});
	}

	@Test
	void videoSizeLimitIncludesTwentyFiveMbButRejectsOneByteMore() {
		byte[] exact = mp4BytesWithSize(25 * MB);
		byte[] oversized = mp4BytesWithSize(25 * MB + 1);

		for (String folder : List.of("reviews", "returns")) {
			assertEquals(MediaType.VIDEO, validation.validate(file("clip.mp4", "video/mp4", exact), folder));

			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> validation.validate(file("clip.mp4", "video/mp4", oversized), folder));

			assertTrue(error.getMessage().contains("25 MB"));
		}
	}

	@Test
	void batchSkipsEmptyEntriesAndPreservesValidFileOrder() {
		MockMultipartFile first = MediaTestFiles.image("first.jpg");

		MockMultipartFile second = MediaTestFiles.image("second.webp");

		MockMultipartFile empty = file("empty.png", "image/png", new byte[0]);

		assertEquals(List.of(first, second),
				validation.validateFiles(new MultipartFile[] { null, first, empty, second }, "products"));

		assertTrue(validation.validateFiles(null, "products").isEmpty());

		assertTrue(validation.validateFiles(new MultipartFile[0], "products").isEmpty());

		assertTrue(validation.validateFiles(new MultipartFile[] { null, empty }, "products").isEmpty());
	}

	@Test
	void batchEnforcesFileCountForEveryFolder() {
		Map<String, Integer> limits = Map.of("avatars", 1, "delivery-proofs", 1, "products", 8, "shops", 1,
				"categories", 1, "reviews", 5, "returns", 5);

		limits.forEach((folder, maximum) -> {
			MockMultipartFile upload = MediaTestFiles.image("photo.png");

			MultipartFile[] allowed = Collections.nCopies(maximum, upload).toArray(MultipartFile[]::new);

			MultipartFile[] tooMany = Collections.nCopies(maximum + 1, upload).toArray(MultipartFile[]::new);

			assertEquals(maximum.intValue(), validation.validateFiles(allowed, folder).size());

			assertThrows(IllegalArgumentException.class, () -> validation.validateFiles(tooMany, folder));
		});
	}

	@Test
	void batchRejectsInvalidMemberRatherThanSilentlySkippingIt() {
		MockMultipartFile good = MediaTestFiles.image("photo.png");

		MockMultipartFile bad = file("photo.jpg", "image/png", MediaTestFiles.pngBytes());

		assertThrows(IllegalArgumentException.class,
				() -> validation.validateFiles(new MultipartFile[] { good, bad }, "products"));

		MockMultipartFile oversized = file("clip.mp4", "video/mp4", mp4BytesWithSize(25 * MB + 1));

		assertThrows(IllegalArgumentException.class,
				() -> validation.validateFiles(new MultipartFile[] { good, oversized }, "returns"));
	}

	@Test
	void rejectsUnknownFoldersEvenWhenBatchIsEmpty() {
		for (String folder : new String[] { null, "../products", "/products", "Products", "unknown", "" }) {

			assertThrows(IllegalArgumentException.class,
					() -> validation.validate(MediaTestFiles.image("photo.png"), folder));

			assertThrows(IllegalArgumentException.class, () -> validation.validateFiles(null, folder));

			assertThrows(IllegalArgumentException.class, () -> validation.validateFiles(new MultipartFile[0], folder));
		}
	}

	@Test
	void rejectsPathControlCharactersAndOverlongFilenames() {
		for (String filename : List.of("../photo.png", "folder/photo.png", "..\\photo.png", "C:\\fakepath\\photo.png",
				"bad\nname.png", "bad" + (char) 0 + ".png", "a".repeat(252) + ".png")) {

			assertThrows(IllegalArgumentException.class,
					() -> validation.validate(file(filename, "image/png", MediaTestFiles.pngBytes()), "products"));
		}
	}

	@Test
	void rejectsSpoofedContentDespiteMatchingMimeAndExtension() {
		byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

		for (String extension : List.of("png", "jpg", "webp")) {
			String mime = extension.equals("jpg") ? "image/jpeg" : "image/" + extension;

			assertThrows(IllegalArgumentException.class,
					() -> validation.validate(file("photo." + extension, mime, html), "products"));
		}

		assertThrows(IllegalArgumentException.class,
				() -> validation.validate(file("photo.png", "image/png", MediaTestFiles.jpegBytes()), "products"));
	}

	@Test
	void rejectsTruncatedImagesWithIntactSignatures() {
		assertThrows(IllegalArgumentException.class, () -> validation
				.validate(file("photo.png", "image/png", Arrays.copyOf(MediaTestFiles.pngBytes(), 24)), "products"));

		assertThrows(IllegalArgumentException.class, () -> validation
				.validate(file("photo.jpg", "image/jpeg", Arrays.copyOf(MediaTestFiles.jpegBytes(), 20)), "products"));
	}

	@Test
	void rejectsTruncatedWebpContainer() {
		byte[] webp = MediaTestFiles.webpBytes();

		assertThrows(IllegalArgumentException.class, () -> validation
				.validate(file("photo.webp", "image/webp", Arrays.copyOf(webp, webp.length - 1)), "products"));
	}

	@Test
	void rejectsImageDimensionsAbovePixelLimitBeforeDecodingPixels() {
		byte[] png = MediaTestFiles.pngBytes();

		ByteBuffer.wrap(png).putInt(16, 5001).putInt(20, 5000);

		CRC32 crc = new CRC32();
		crc.update(png, 12, 17);

		ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> validation.validate(file("large.png", "image/png", png), "products"));

		assertTrue(error.getMessage().contains("25 triệu pixel"));
	}

	@Test
	void rejectsBodySizeDifferentFromReportedSize() {
		MockMultipartFile upload = new MockMultipartFile("file", "photo.png", "image/png", MediaTestFiles.pngBytes()) {

			@Override
			public long getSize() {
				return 1;
			}
		};

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> validation.validate(upload, "products"));

		assertTrue(error.getMessage().contains("Dung lượng thực tế"));
	}

	@Test
	void wrapsUnreadableUploadStreamAsValidationError() {
		MockMultipartFile upload = new MockMultipartFile("file", "photo.png", "image/png", MediaTestFiles.pngBytes()) {

			@Override
			public InputStream getInputStream() throws IOException {
				throw new IOException("Simulated unreadable upload");
			}
		};

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> validation.validate(upload, "products"));

		assertInstanceOf(IOException.class, error.getCause());
	}

	@Test
	void rejectsMp4AndMovWithSwappedDeclaredFormats() {
		assertThrows(IllegalArgumentException.class,
				() -> validation.validate(file("clip.mov", "video/quicktime", videoBytes("mp4")), "returns"));

		assertThrows(IllegalArgumentException.class,
				() -> validation.validate(file("clip.mp4", "video/mp4", videoBytes("mov")), "returns"));
	}

	@Test
	void rejectsTruncatedAndForgedVideoContainers() {
		for (String extension : List.of("mp4", "mov", "webm")) {
			byte[] bytes = videoBytes(extension);

			assertThrows(IllegalArgumentException.class,
					() -> validation.validate(
							file("clip." + extension, videoMime(extension), Arrays.copyOf(bytes, bytes.length - 1)),
							"returns"));

			assertThrows(IllegalArgumentException.class, () -> validation
					.validate(file("clip." + extension, videoMime(extension), new byte[64]), "returns"));
		}
	}

	@Test
	void acceptsFiveMixedImagesAndVideosForReturnEvidence() {
		MultipartFile[] uploads = { MediaTestFiles.image("photo.png"), MediaTestFiles.image("photo.jpg"), video("mp4"),
				video("mov"), video("webm") };

		assertEquals(5, validation.validateFiles(uploads, "returns").size());
	}

	private static MockMultipartFile file(String filename, String mime, byte[] content) {

		return new MockMultipartFile("file", filename, mime, content);
	}

	private static MockMultipartFile video(String extension) {
		return file("clip." + extension, videoMime(extension), videoBytes(extension));
	}

	private static String videoMime(String extension) {
		return switch (extension) {
		case "mp4" -> "video/mp4";
		case "mov" -> "video/quicktime";
		case "webm" -> "video/webm";

		default -> throw new IllegalArgumentException("Unknown video fixture");
		};
	}

	private static byte[] videoBytes(String extension) {
		return VIDEOS.get(extension).clone();
	}

	/*
	 * Mở rộng video thật bằng một MP4 free box hợp lệ để đạt đúng dung lượng cần
	 * kiểm tra.
	 */
	private static byte[] mp4BytesWithSize(int totalBytes) {
		byte[] original = videoBytes("mp4");

		if (totalBytes < original.length + 8) {
			throw new IllegalArgumentException("Video mẫu cần đủ chỗ cho free box.");
		}

		byte[] result = Arrays.copyOf(original, totalBytes);

		ByteBuffer.wrap(result).putInt(original.length, totalBytes - original.length);

		System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, result, original.length + 4, 4);

		return result;
	}
}