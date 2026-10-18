package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.springframework.mock.web.MockMultipartFile;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.MediaValidationService;

class MediaStorageServiceTests {

	private static final byte[] CONTENT = { 1, 2, 3, 4 };
	private static final String CLOUD_URL = "https://res.cloudinary.com/test/image/upload/photo.png";

	@TempDir
	Path uploadRoot;

	private final MediaValidationService validation = new MediaValidationService();

	@Test
	void nullAndEmptyUploadsDoNotCreateFiles() throws IOException {
		MediaStorageService storage = localStorage();

		assertThat(storage.upload(null, "products")).isNull();
		assertThat(storage.upload(image("empty.png", new byte[0]), "products")).isNull();

		assertRootEmpty();
	}

	@Test
	void localUploadCreatesFolderAndPreservesBytes() throws IOException {
		String url = localStorage().upload(image("photo.png", CONTENT), "products");

		assertThat(url).startsWith("/uploads/products/").endsWith(".png");
		Path stored = localPath(url);
		assertThat(stored.getParent()).isEqualTo(uploadRoot.resolve("products"));
		assertThat(Files.readAllBytes(stored)).containsExactly(CONTENT);
		String filename = stored.getFileName().toString();
		assertThat(UUID.fromString(filename.substring(0, filename.length() - 4))).isNotNull();
	}

	@Test
	void repeatedOriginalNameProducesDistinctFilesWithoutOverwriting() throws IOException {
		MediaStorageService storage = localStorage();
		byte[] secondContent = { 9, 8, 7 };

		String first = storage.upload(image("photo.PNG", CONTENT), "products");
		String second = storage.upload(image("photo.PNG", secondContent), "products");

		assertThat(first).isNotEqualTo(second).endsWith(".png");
		assertThat(second).endsWith(".png");
		assertThat(Files.readAllBytes(localPath(first))).containsExactly(CONTENT);
		assertThat(Files.readAllBytes(localPath(second))).containsExactly(secondContent);
		try (var files = Files.list(uploadRoot.resolve("products"))) {
			assertThat(files.count()).isEqualTo(2);
		}
	}

	@Test
	void originalFilenameCannotChooseTheDestinationPath() throws IOException {
		String url = localStorage().upload(image("../../outside.PNG", CONTENT), "products");

		Path stored = localPath(url);
		assertThat(stored.getParent()).isEqualTo(uploadRoot.resolve("products"));
		assertThat(stored.getFileName().toString()).doesNotContain("outside", "..");
		assertThat(Files.readAllBytes(stored)).containsExactly(CONTENT);
		try (var entries = Files.list(uploadRoot)) {
			assertThat(entries.toList()).containsExactly(uploadRoot.resolve("products"));
		}
	}

	@Test
	void invalidMimeIsRejectedBeforeWritingLocalFiles() throws IOException {
		MockMultipartFile invalid = new MockMultipartFile("file", "photo.png", "application/octet-stream", CONTENT);

		assertThatThrownBy(() -> localStorage().upload(invalid, "products"))
				.isInstanceOf(IllegalArgumentException.class);

		assertRootEmpty();
	}

	@Test
	void unknownAndTraversalFoldersAreRejectedBeforeWriting() throws IOException {
		MediaStorageService storage = localStorage();
		for (String folder : List.of("unknown", "../outside", "products/../../outside")) {
			assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), folder)).as("Reject folder %s", folder)
					.isInstanceOf(IllegalArgumentException.class);
		}

		assertRootEmpty();
	}

	@Test
	void localIoFailureIsWrappedWithItsCause() throws IOException {
		Path blockedRoot = uploadRoot.resolve("regular-file");
		Files.write(blockedRoot, CONTENT);
		MediaStorageService storage = new MediaStorageService(validation, "", "", "", blockedRoot.toString());

		assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), "products"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên.")
				.hasCauseInstanceOf(IOException.class);

		assertThat(Files.readAllBytes(blockedRoot)).containsExactly(CONTENT);
	}

	@Test
	void eachMissingCloudinaryCredentialFallsBackToLocalStorage() throws IOException {
		String[][] credentials = { { "", "test-key", "test-secret" }, { "test-cloud", "", "test-secret" },
				{ "test-cloud", "test-key", "" } };

		try (MockedConstruction<Cloudinary> clients = mockConstruction(Cloudinary.class)) {
			for (String[] credential : credentials) {
				MediaStorageService storage = new MediaStorageService(validation, credential[0], credential[1],
						credential[2], uploadRoot.toString());
				String url = storage.upload(image("photo.png", CONTENT), "products");

				assertThat(url).startsWith("/uploads/products/");
				assertThat(Files.readAllBytes(localPath(url))).containsExactly(CONTENT);
			}
			assertThat(clients.constructed()).isEmpty();
		}
	}

	@Test
	void cloudinaryUploadReturnsSecureUrlAndDoesNotWriteLocally() throws Exception {
		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			assertThat(storage.upload(image("photo.png", CONTENT), "products")).isEqualTo(CLOUD_URL);

			assertThat(clients.constructed()).hasSize(1);
			verify(clients.constructed().get(0).uploader()).upload(eq(CONTENT),
					argThat(options -> "utetra/products".equals(options.get("folder"))
							&& "auto".equals(options.get("resource_type"))));
			assertRootEmpty();
		}
	}

	@Test
	void cloudinaryAcceptsReturnVideoWithAutomaticResourceType() throws Exception {
		MockMultipartFile video = new MockMultipartFile("file", "proof.mp4", "video/mp4", CONTENT);
		String videoUrl = "https://res.cloudinary.com/test/video/upload/proof.mp4";

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();
			Cloudinary cloud = clients.constructed().get(0);
			when(cloud.uploader().upload(any(byte[].class), anyMap())).thenReturn(Map.of("secure_url", videoUrl));

			assertThat(storage.upload(video, "returns")).isEqualTo(videoUrl);
			verify(cloud.uploader()).upload(eq(CONTENT),
					argThat(options -> "utetra/returns".equals(options.get("folder"))
							&& "auto".equals(options.get("resource_type"))));
			assertRootEmpty();
		}
	}

	@Test
	void invalidUploadDoesNotReachCloudinary() throws Exception {
		MockMultipartFile video = new MockMultipartFile("file", "clip.mp4", "video/mp4", CONTENT);

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			assertThatThrownBy(() -> storage.upload(video, "products")).isInstanceOf(IllegalArgumentException.class);
			assertThat(storage.upload(null, "products")).isNull();
			assertThat(storage.upload(image("empty.png", new byte[0]), "products")).isNull();

			verify(clients.constructed().get(0).uploader(), never()).upload(any(byte[].class), anyMap());
			assertRootEmpty();
		}
	}

	@Test
	void cloudinaryIoFailureIsWrappedWithoutCreatingLocalFiles() throws Exception {
		IOException failure = new IOException("Simulated Cloudinary failure");

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();
			when(clients.constructed().get(0).uploader().upload(any(byte[].class), anyMap())).thenThrow(failure);

			assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), "products"))
					.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên.").hasCause(failure);

			assertRootEmpty();
		}
	}

	private MediaStorageService localStorage() {
		return new MediaStorageService(validation, "", "", "", uploadRoot.toString());
	}

	private MediaStorageService cloudStorage() {
		return new MediaStorageService(validation, "test-cloud", "test-key", "test-secret", uploadRoot.toString());
	}

	private MockedConstruction<Cloudinary> cloudinaryMocks() {
		return mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
				(cloud, context) -> when(cloud.uploader().upload(any(byte[].class), anyMap()))
						.thenReturn(Map.of("secure_url", CLOUD_URL)));
	}

	private MockMultipartFile image(String filename, byte[] bytes) {
		return new MockMultipartFile("file", filename, "image/png", bytes);
	}

	private Path localPath(String url) {
		assertThat(url).startsWith("/uploads/");
		Path stored = uploadRoot.resolve(url.substring("/uploads/".length())).normalize();
		assertThat(stored.startsWith(uploadRoot)).isTrue();
		return stored;
	}

	private void assertRootEmpty() throws IOException {
		try (var entries = Files.list(uploadRoot)) {
			assertThat(entries.count()).isZero();
		}
	}
}
