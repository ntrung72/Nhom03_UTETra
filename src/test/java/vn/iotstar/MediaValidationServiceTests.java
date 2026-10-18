package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.service.MediaValidationService;

class MediaValidationServiceTests {

	private static final long MB = 1024L * 1024;
	private final MediaValidationService validation = new MediaValidationService();

	@Test
	void acceptsSupportedImageFormats() {
		for (String extension : List.of("jpg", "jpeg", "png", "webp")) {
			String mime = extension.equals("jpg") || extension.equals("jpeg") ? "image/jpeg" : "image/" + extension;
			assertThat(validation.validate(upload("photo." + extension, mime, 100), "products"))
					.as("Image format %s", extension).isEqualTo(MediaType.IMAGE);
		}
	}

	@Test
	void normalizesMimeWhitespaceAndFilenameCase() {
		assertThat(validation.validate(upload("PHOTO.JPEG", " IMAGE/JPEG ", 100), "products"))
				.isEqualTo(MediaType.IMAGE);
		assertThat(validation.validate(upload("PHOTO.PNG", "IMAGE/PNG", 100), "shops")).isEqualTo(MediaType.IMAGE);
		assertThat(validation.validate(upload("CLIP.MOV", " VIDEO/QUICKTIME ", 100), "returns"))
				.isEqualTo(MediaType.VIDEO);
	}

	@Test
	void acceptsSupportedVideosForReviewsAndReturns() {
		Map<String, String> videos = Map.of("mp4", "video/mp4", "webm", "video/webm", "mov", "video/quicktime");
		for (String folder : List.of("reviews", "returns")) {
			videos.forEach(
					(extension, mime) -> assertThat(validation.validate(upload("clip." + extension, mime, 100), folder))
							.as("%s in %s", mime, folder).isEqualTo(MediaType.VIDEO));
		}
	}

	@Test
	void rejectsVideosForEveryImageOnlyFolder() {
		for (String folder : List.of("avatars", "delivery-proofs", "products", "shops", "categories")) {
			assertThatThrownBy(() -> validation.validate(upload("clip.mp4", "video/mp4", 100), folder))
					.as("Video must be rejected in %s", folder).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsMissingOrEmptySingleUpload() {
		assertThatThrownBy(() -> validation.validate(null, "products")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> validation.validate(upload("empty.png", "image/png", 0), "products"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsUnknownMimesAndMismatchedExtensions() {
		for (MultipartFile file : List.of(upload("photo.jpg", "image/png", 100),
				upload("photo.png.exe", "image/png", 100), upload("image.svg", "image/svg+xml", 100),
				upload("photo.png", "application/octet-stream", 100), upload("page.html", "text/html", 100),
				upload("clip.mp4", "image/jpeg", 100), upload("photo.png", null, 100))) {
			assertThatThrownBy(() -> validation.validate(file, "returns")).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsMissingFilenameOrExtension() {
		for (String filename : new String[] { null, "", "photo", "photo." }) {
			assertThatThrownBy(() -> validation.validate(upload(filename, "image/png", 100), "products"))
					.as("Missing or invalid filename: %s", filename).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void imageSizeLimitIncludesExactBoundaryButRejectsOneByteMore() {
		Map<String, Integer> limits = Map.of("avatars", 5, "delivery-proofs", 10, "products", 10, "shops", 10,
				"categories", 10, "reviews", 10, "returns", 10);
		limits.forEach((folder, megabytes) -> {
			long limit = megabytes * MB;
			assertThat(validation.validate(upload("photo.png", "image/png", limit), folder))
					.as("Exact image size boundary in %s", folder).isEqualTo(MediaType.IMAGE);
			assertThatThrownBy(() -> validation.validate(upload("photo.png", "image/png", limit + 1), folder))
					.as("Oversized image in %s", folder).isInstanceOf(IllegalArgumentException.class);
		});
	}

	@Test
	void videoSizeLimitIncludesTwentyFiveMbButRejectsOneByteMore() {
		for (String folder : List.of("reviews", "returns")) {
			assertThat(validation.validate(upload("clip.mp4", "video/mp4", 25 * MB), folder))
					.isEqualTo(MediaType.VIDEO);
			assertThatThrownBy(() -> validation.validate(upload("clip.mp4", "video/mp4", 25 * MB + 1), folder))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void batchSkipsEmptyEntriesAndPreservesValidFileOrder() {
		var first = upload("first.jpg", "image/jpeg", 100);
		var second = upload("second.webp", "image/webp", 100);
		var empty = upload("empty.png", "image/png", 0);

		assertThat(validation.validateFiles(new MultipartFile[] { null, first, empty, second }, "products"))
				.containsExactly(first, second);
		assertThat(validation.validateFiles(null, "products")).isEmpty();
		assertThat(validation.validateFiles(new MultipartFile[0], "products")).isEmpty();
		assertThat(validation.validateFiles(new MultipartFile[] { null, empty }, "products")).isEmpty();
	}

	@Test
	void batchEnforcesFileCountForEveryFolder() {
		Map<String, Integer> limits = Map.of("avatars", 1, "delivery-proofs", 1, "products", 8, "shops", 1,
				"categories", 1, "reviews", 5, "returns", 5);
		limits.forEach((folder, maximum) -> {
			var file = upload("photo.png", "image/png", 100);
			var allowed = Collections.nCopies(maximum, file).toArray(MultipartFile[]::new);
			var tooMany = Collections.nCopies(maximum + 1, file).toArray(MultipartFile[]::new);
			assertThat(validation.validateFiles(allowed, folder)).as("Maximum file count in %s", folder)
					.hasSize(maximum);
			assertThatThrownBy(() -> validation.validateFiles(tooMany, folder)).as("Too many files in %s", folder)
					.isInstanceOf(IllegalArgumentException.class);
		});
	}

	@Test
	void batchRejectsInvalidMemberRatherThanSilentlySkippingIt() {
		var good = upload("photo.png", "image/png", 100);
		var bad = upload("photo.jpg", "image/png", 100);
		assertThatThrownBy(() -> validation.validateFiles(new MultipartFile[] { good, bad }, "products"))
				.isInstanceOf(IllegalArgumentException.class);
		var oversized = upload("clip.mp4", "video/mp4", 25 * MB + 1);
		assertThatThrownBy(() -> validation.validateFiles(new MultipartFile[] { good, oversized }, "returns"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsUnknownFoldersEvenWhenBatchIsEmpty() {
		for (String folder : List.of("../products", "/products", "Products", "unknown", "")) {
			assertThatThrownBy(() -> validation.validate(upload("photo.png", "image/png", 100), folder))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> validation.validateFiles(null, folder))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private MultipartFile upload(String filename, String mime, long size) {
		MultipartFile file = mock(MultipartFile.class);
		when(file.getOriginalFilename()).thenReturn(filename);
		when(file.getContentType()).thenReturn(mime);
		when(file.getSize()).thenReturn(size);
		when(file.isEmpty()).thenReturn(size == 0);
		return file;
	}
}
