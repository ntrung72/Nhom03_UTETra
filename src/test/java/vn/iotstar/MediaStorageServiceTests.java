package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.MediaValidationService;
import vn.iotstar.support.MediaTestFiles;

class MediaStorageServiceTests {

	private static final byte[] CONTENT = MediaTestFiles.pngBytes();
	private static final String CLOUD_NAME = "test-cloud";

	@TempDir
	Path testRoot;

	private final MediaValidationService validation = new MediaValidationService();

	@Test
	void nullAndEmptyUploadsDoNotCreateFiles() throws IOException {
		MediaStorageService storage = localStorage();

		assertThat(storage.upload(null, "products")).isNull();

		assertThat(storage.upload(image("empty.png", new byte[0]), "products")).isNull();

		assertNoStoredFiles();
	}

	@Test
	void localUploadCreatesFolderAndPreservesBytes() throws IOException {
		String url = localStorage().upload(image("photo.png", CONTENT), "products");

		assertThat(url).startsWith("/uploads/products/").endsWith(".png");

		Path stored = localPath(url);

		assertThat(stored.getParent()).isEqualTo(publicRoot().resolve("products"));

		assertThat(Files.readAllBytes(stored)).containsExactly(CONTENT);

		String name = stored.getFileName().toString();

		assertThat(UUID.fromString(name.substring(0, name.length() - 4))).isNotNull();
	}

	@Test
	void repeatedOriginalNameProducesDistinctFilesWithoutOverwriting() throws IOException {

		MediaStorageService storage = localStorage();
		byte[] secondContent = MediaTestFiles.pngBytesWithSize(1024);

		String first = storage.upload(image("photo.PNG", CONTENT), "products");

		String second = storage.upload(image("photo.PNG", secondContent), "products");

		assertThat(first).isNotEqualTo(second).endsWith(".png");

		assertThat(second).endsWith(".png");

		assertThat(Files.readAllBytes(localPath(first))).containsExactly(CONTENT);

		assertThat(Files.readAllBytes(localPath(second))).containsExactly(secondContent);

		try (var files = Files.list(publicRoot().resolve("products"))) {
			assertThat(files.count()).isEqualTo(2);
		}
	}

	@Test
	void rejectsOriginalFilenameContainingDestinationPaths() throws IOException {

		MediaStorageService storage = localStorage();

		for (String name : List.of("../../outside.PNG", "folder/photo.png", "..\\outside.png")) {

			assertThatThrownBy(() -> storage.upload(image(name, CONTENT), "products"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		assertNoStoredFiles();
	}

	@Test
	void invalidMimeIsRejectedBeforeWritingLocalFiles() throws IOException {
		MockMultipartFile invalid = new MockMultipartFile("file", "photo.png", "application/octet-stream", CONTENT);

		assertThatThrownBy(() -> localStorage().upload(invalid, "products"))
				.isInstanceOf(IllegalArgumentException.class);

		assertNoStoredFiles();
	}

	@Test
	void unknownAndTraversalFoldersAreRejectedBeforeWriting() throws IOException {

		MediaStorageService storage = localStorage();

		for (String folder : new String[] { null, "", "unknown", "../outside", "products/../../outside" }) {

			assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), folder))
					.isInstanceOf(IllegalArgumentException.class);

			assertThatThrownBy(() -> storage.upload(null, folder)).isInstanceOf(IllegalArgumentException.class);
		}

		assertNoStoredFiles();
	}

	@Test
	void localIoFailureIsWrappedWithItsCause() throws IOException {
		Path blockedRoot = testRoot.resolve("regular-file");
		Files.write(blockedRoot, CONTENT);

		MediaStorageService storage = new MediaStorageService(validation, "", "", "", blockedRoot.toString(),
				privateRoot().toString());

		assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), "products"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên.")
				.hasCauseInstanceOf(IOException.class);

		assertThat(Files.readAllBytes(blockedRoot)).containsExactly(CONTENT);
	}

	@Test
	void eachMissingCloudinaryCredentialFallsBackToLocalStorage() throws IOException {

		String[][] credentials = { { "", "test-key", "test-secret" }, { CLOUD_NAME, "", "test-secret" },
				{ CLOUD_NAME, "test-key", "" } };

		try (MockedConstruction<Cloudinary> clients = mockConstruction(Cloudinary.class)) {
			for (String[] credential : credentials) {
				MediaStorageService storage = new MediaStorageService(validation, credential[0], credential[1],
						credential[2], publicRoot().toString(), privateRoot().toString());

				String url = storage.upload(image("photo.png", CONTENT), "products");

				assertThat(url).startsWith("/uploads/products/");

				assertThat(Files.readAllBytes(localPath(url))).containsExactly(CONTENT);
			}

			assertThat(clients.constructed()).isEmpty();
		}
	}

	@Test
	void allPublicFoldersUsePublicReferencesWhenCloudinaryIsAbsent() throws IOException {

		MediaStorageService storage = localStorage();

		for (String folder : List.of("avatars", "products", "shops", "categories", "reviews")) {

			String reference = storage.upload(image("photo.png", CONTENT), folder);

			assertThat(reference).startsWith("/uploads/" + folder + "/");

			assertThat(Files.readAllBytes(localPath(reference))).containsExactly(CONTENT);
		}

		assertThat(Files.exists(privateRoot())).isFalse();
	}

	@Test
	void returnAndDeliveryEvidenceStayOutsidePublicRoot() throws IOException {

		MediaStorageService storage = localStorage();

		for (String folder : List.of("returns", "delivery-proofs")) {
			String reference = storage.upload(image("proof.png", CONTENT), folder);

			assertThat(reference).startsWith("private:" + folder + "/");

			assertThat(Files.readAllBytes(privatePath(reference))).containsExactly(CONTENT);
		}

		assertThat(Files.exists(publicRoot())).isFalse();
	}

	@Test
	void privateEvidenceIsNotUploadedToCloudinaryEvenWhenConfigured() throws Exception {

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			for (String folder : List.of("returns", "delivery-proofs")) {
				String reference = storage.upload(image("proof.png", CONTENT), folder);

				assertThat(reference).startsWith("private:" + folder + "/");

				assertThat(Files.readAllBytes(privatePath(reference))).containsExactly(CONTENT);
			}

			verify(clients.constructed().get(0).uploader(), never()).upload(any(byte[].class), anyMap());

			assertThat(Files.exists(publicRoot())).isFalse();
		}
	}

	@Test
	void readPrivateReturnsStoredBytesAndContentType() throws IOException {

		MediaStorageService storage = localStorage();

		String reference = storage.upload(image("proof.png", CONTENT), "returns");

		MediaStorageService.PrivateMedia media = storage.readPrivate(reference, "returns");

		assertThat(media.contentType()).isEqualTo("image/png");

		try (InputStream input = media.resource().getInputStream()) {
			assertThat(input.readAllBytes()).containsExactly(CONTENT);
		}
	}

	@Test
	void privateReadRejectsWrongFolderAndPublicReferences() {
		MediaStorageService storage = localStorage();

		String privateReference = storage.upload(image("proof.png", CONTENT), "returns");

		String publicReference = storage.upload(image("photo.png", CONTENT), "products");

		assertThatThrownBy(() -> storage.readPrivate(privateReference, "delivery-proofs"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> storage.readPrivate(publicReference, "returns"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> storage.readPrivate(privateReference, "products"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void privateReadRejectsTraversalMalformedAndMissingReferences() {
		MediaStorageService storage = localStorage();

		for (String reference : new String[] { null, "", "private:returns/../outside.png",
				"private:returns/..\\outside.png", "private:returns/%2e%2e.png", "private:returns/nested/proof.png",
				"private:returns/missing.png" }) {

			assertThatThrownBy(() -> storage.readPrivate(reference, "returns"))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void publicAndPrivateRootsCannotOverlap() {
		for (Path[] roots : List.of(new Path[] { publicRoot(), publicRoot() },
				new Path[] { publicRoot(), publicRoot().resolve("private") },
				new Path[] { privateRoot().resolve("public"), privateRoot() })) {

			assertThatThrownBy(
					() -> new MediaStorageService(validation, "", "", "", roots[0].toString(), roots[1].toString()))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void deletionIgnoresForeignPathsAndRemovesManagedFileIdempotently() throws IOException {

		MediaStorageService storage = localStorage();

		String reference = storage.upload(image("photo.png", CONTENT), "products");

		Path stored = localPath(reference);
		Path outside = testRoot.resolve("outside.png");

		Files.write(outside, CONTENT);

		for (String foreign : List.of("/uploads/products/../../outside.png", "/uploads/unknown/outside.png",
				"/images/product-placeholder.svg", outside.toUri().toString(), "https://example.com/outside.png")) {

			storage.deleteAfterCommit(foreign);
		}

		assertThat(Files.exists(outside)).isTrue();
		assertThat(Files.exists(stored)).isTrue();

		storage.deleteAfterCommit(reference);
		storage.deleteAfterCommit(reference);

		assertThat(Files.exists(stored)).isFalse();

		assertThat(Files.readAllBytes(outside)).containsExactly(CONTENT);
	}

	@Test
	void privateManagedFileCanBeDeletedWithoutTouchingPublicFiles() {
		MediaStorageService storage = localStorage();

		String proof = storage.upload(image("proof.png", CONTENT), "returns");

		String publicImage = storage.upload(image("photo.png", CONTENT), "products");

		storage.deleteAfterCommit(proof);

		assertThat(Files.exists(privatePath(proof))).isFalse();
		assertThat(Files.exists(localPath(publicImage))).isTrue();

		assertThatThrownBy(() -> storage.readPrivate(proof, "returns")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cloudinaryUploadUsesOwnedPublicIdAndDoesNotWriteLocally() throws Exception {

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			String url = storage.upload(image("photo.png", CONTENT), "products");

			assertThat(url).matches("https://res\\.cloudinary\\.com/" + CLOUD_NAME
					+ "/image/upload/v123/utetra/products/[0-9a-f-]+\\.png");

			verify(clients.constructed().get(0).uploader()).upload(eq(CONTENT),
					argThat(options -> options.get("public_id") instanceof String id
							&& id.startsWith("utetra/products/") && "image".equals(options.get("resource_type"))
							&& Boolean.FALSE.equals(options.get("overwrite"))));

			assertNoStoredFiles();
		}
	}

	@Test
	void invalidUploadDoesNotReachCloudinary() throws Exception {
		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			assertThatThrownBy(() -> storage.upload(image("photo.png", new byte[] { 1, 2, 3 }), "products"))
					.isInstanceOf(IllegalArgumentException.class);

			assertThat(storage.upload(null, "products")).isNull();

			assertThat(storage.upload(image("empty.png", new byte[0]), "products")).isNull();

			verify(clients.constructed().get(0).uploader(), never()).upload(any(byte[].class), anyMap());

			assertNoStoredFiles();
		}
	}

	@Test
	void cloudinaryIoFailureAttemptsCleanupAndPreservesOriginalCause() throws Exception {

		IOException failure = new IOException("Simulated Cloudinary failure");

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();
			Cloudinary cloud = clients.constructed().get(0);

			when(cloud.uploader().upload(any(byte[].class), anyMap())).thenThrow(failure);

			assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), "products"))
					.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên Cloudinary.")
					.hasCause(failure);

			verify(cloud.uploader()).destroy(startsWith("utetra/products/"), anyMap());

			assertNoStoredFiles();
		}
	}

	@Test
	void malformedCloudinaryResponsesAreRejectedAndNewAssetIsCleanedUp() throws Exception {

		List<Map<String, String>> responses = List.of(Map.of(), Map.of("secure_url", ""),
				Map.of("secure_url", "https://example.com/photo.png"),
				Map.of("secure_url", "https://res.cloudinary.com/" + CLOUD_NAME
						+ "/image/upload/v123/utetra/products/another-file.png"));

		for (Map<String, String> response : responses) {
			try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
				MediaStorageService storage = cloudStorage();
				Cloudinary cloud = clients.constructed().get(0);

				when(cloud.uploader().upload(any(byte[].class), anyMap())).thenReturn(response);

				assertThatThrownBy(() -> storage.upload(image("photo.png", CONTENT), "products"))
						.isInstanceOf(IllegalStateException.class);

				verify(cloud.uploader()).destroy(startsWith("utetra/products/"), anyMap());

				assertNoStoredFiles();
			}
		}
	}

	@Test
	void deletionIgnoresForeignCloudinaryUrls() throws Exception {
		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			for (String url : List.of("https://example.com/photo.png",
					"https://res.cloudinary.com/other-cloud/image/upload/v123/utetra/products/a.png",
					"https://res.cloudinary.com/" + CLOUD_NAME + "/image/upload/v123/other/a.png",
					"https://res.cloudinary.com/" + CLOUD_NAME + "/image/upload/v123/utetra/products/a.png?extra=1")) {

				storage.deleteAfterCommit(url);
			}

			verify(clients.constructed().get(0).uploader(), never()).destroy(anyString(), anyMap());
		}
	}

	@Test
	void deletionOfOwnedCloudinaryAssetUsesPublicIdAndResourceType() throws Exception {

		try (MockedConstruction<Cloudinary> clients = cloudinaryMocks()) {
			MediaStorageService storage = cloudStorage();

			storage.deleteAfterCommit(
					"https://res.cloudinary.com/" + CLOUD_NAME + "/image/upload/v123/utetra/products/old-id.png");

			verify(clients.constructed().get(0).uploader()).destroy(eq("utetra/products/old-id"),
					argThat(options -> "image".equals(options.get("resource_type"))
							&& Boolean.TRUE.equals(options.get("invalidate"))));
		}
	}

	@Test
	void failedLocalCopyRemovesPartiallyWrittenFile() throws IOException {
		MediaValidationService validator = mock(MediaValidationService.class);

		when(validator.validate(any(MultipartFile.class), eq("products"))).thenReturn(MediaType.IMAGE);

		MediaStorageService storage = new MediaStorageService(validator, "", "", "", publicRoot().toString(),
				privateRoot().toString());

		MockMultipartFile broken = new MockMultipartFile("file", "photo.png", "image/png", CONTENT) {

			@Override
			public InputStream getInputStream() {
				return new InputStream() {

					private int index;

					@Override
					public int read() throws IOException {
						if (index >= 8) {
							throw new IOException("Simulated failure after partial copy");
						}

						return CONTENT[index++] & 0xFF;
					}
				};
			}
		};

		assertThatThrownBy(() -> storage.upload(broken, "products")).isInstanceOf(IllegalStateException.class)
				.hasCauseInstanceOf(IOException.class);

		assertNoStoredFiles();
	}

	private MediaStorageService localStorage() {
		return new MediaStorageService(validation, "", "", "", publicRoot().toString(), privateRoot().toString());
	}

	private MediaStorageService cloudStorage() {
		return new MediaStorageService(validation, CLOUD_NAME, "test-key", "test-secret", publicRoot().toString(),
				privateRoot().toString());
	}

	private MockedConstruction<Cloudinary> cloudinaryMocks() {
		return mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
				(cloud, context) -> {
					when(cloud.uploader().upload(any(byte[].class), anyMap())).thenAnswer(invocation -> {
						Map<?, ?> options = invocation.getArgument(1);

						return Map.of("secure_url", "https://res.cloudinary.com/" + CLOUD_NAME + "/"
								+ options.get("resource_type") + "/upload/v123/" + options.get("public_id") + ".png");
					});

					when(cloud.uploader().destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
				});
	}

	private MockMultipartFile image(String filename, byte[] bytes) {

		return new MockMultipartFile("file", filename, "image/png", bytes);
	}

	private Path publicRoot() {
		return testRoot.resolve("public");
	}

	private Path privateRoot() {
		return testRoot.resolve("private");
	}

	private Path localPath(String reference) {
		assertThat(reference).startsWith("/uploads/");

		Path file = publicRoot().resolve(reference.substring("/uploads/".length())).normalize();

		assertThat(file.startsWith(publicRoot())).isTrue();

		return file;
	}

	private Path privatePath(String reference) {
		assertThat(reference).startsWith("private:");

		Path file = privateRoot().resolve(reference.substring("private:".length())).normalize();

		assertThat(file.startsWith(privateRoot())).isTrue();

		return file;
	}

	private void assertNoStoredFiles() throws IOException {
		try (var paths = Files.walk(testRoot)) {
			assertThat(paths.filter(Files::isRegularFile).count()).isZero();
		}
	}
}