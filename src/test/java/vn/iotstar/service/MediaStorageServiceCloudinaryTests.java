package vn.iotstar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.iotstar.support.MediaTestFiles;

class MediaStorageServiceCloudinaryTests {

	private static final String CLOUD_NAME = "test-cloud";

	private static final String IMAGE_URL_PREFIX = "https://res.cloudinary.com/" + CLOUD_NAME + "/image/upload/v123/";

	@TempDir
	Path testRoot;

	private Cloudinary cloudinary;
	private Uploader uploader;
	private MediaStorageService storage;

	@BeforeEach
	void setUp() throws IOException {
		cloudinary = mock(Cloudinary.class);
		uploader = mock(Uploader.class);

		when(cloudinary.uploader()).thenReturn(uploader);

		when(uploader.upload(any(byte[].class), anyMap())).thenAnswer(invocation -> {
			Map<?, ?> options = invocation.getArgument(1);

			return Map.of("secure_url", IMAGE_URL_PREFIX + options.get("public_id") + ".png");
		});

		when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));

		storage = new MediaStorageService(new MediaValidationService(), cloudinary, CLOUD_NAME,
				testRoot.resolve("public"), testRoot.resolve("private"));
	}

	@AfterEach
	void clearTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}

		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void committingAnUploadKeepsTheNewAsset() throws IOException {
		beginCallbacks();

		String url = storage.upload(MediaTestFiles.image("cover.png"), "products");

		String publicId = imagePublicId(url);

		assertThat(publicId).startsWith("utetra/products/");

		verify(uploader).upload(eq(MediaTestFiles.pngBytes()),
				eq(Map.of("public_id", publicId, "resource_type", "image", "overwrite", false)));

		verify(uploader, never()).destroy(anyString(), anyMap());

		commitCallbacks();

		verify(uploader, never()).destroy(anyString(), anyMap());
	}

	@Test
	void rollingBackDeletesEveryNewAssetUsingItsExactPublicId() throws IOException {

		beginCallbacks();

		String avatar = storage.upload(MediaTestFiles.image("avatar.png"), "avatars");

		String product = storage.upload(MediaTestFiles.image("product.png"), "products");

		verify(uploader, never()).destroy(anyString(), anyMap());

		rollbackCallbacks();

		verify(uploader).destroy(eq(imagePublicId(avatar)), eq(destroyOptions("image")));

		verify(uploader).destroy(eq(imagePublicId(product)), eq(destroyOptions("image")));

		verify(uploader, times(2)).destroy(anyString(), anyMap());
	}

	@Test
	void replacingAnAssetDeletesTheOldOneOnlyAfterCommit() throws IOException {

		String oldId = "utetra/shops/old-cover";

		beginCallbacks();

		String newUrl = storage.upload(MediaTestFiles.image("shop.png"), "shops");

		storage.deleteAfterCommit(IMAGE_URL_PREFIX + oldId + ".png");

		verify(uploader, never()).destroy(anyString(), anyMap());

		commitCallbacks();

		verify(uploader).destroy(eq(oldId), eq(destroyOptions("image")));

		verify(uploader, never()).destroy(eq(imagePublicId(newUrl)), anyMap());

		verify(uploader, times(1)).destroy(anyString(), anyMap());
	}

	@Test
	void replacingAnAssetThenRollingBackKeepsTheOldOneAndDeletesTheNewOne() throws IOException {

		String oldId = "utetra/shops/old-cover";

		beginCallbacks();

		String newUrl = storage.upload(MediaTestFiles.image("shop.png"), "shops");

		storage.deleteAfterCommit(IMAGE_URL_PREFIX + oldId + ".png");

		verify(uploader, never()).destroy(anyString(), anyMap());

		rollbackCallbacks();

		verify(uploader).destroy(eq(imagePublicId(newUrl)), eq(destroyOptions("image")));

		verify(uploader, never()).destroy(eq(oldId), anyMap());

		verify(uploader, times(1)).destroy(anyString(), anyMap());
	}

	@Test
	void aLaterUploadFailureCleansItsPartialAssetThenRollbackCleansEarlierUploads() throws IOException {

		IOException failure = new IOException("Second upload interrupted");

		AtomicInteger calls = new AtomicInteger();
		AtomicReference<String> failedId = new AtomicReference<>();

		when(uploader.upload(any(byte[].class), anyMap())).thenAnswer(invocation -> {
			Map<?, ?> options = invocation.getArgument(1);
			String publicId = (String) options.get("public_id");

			if (calls.incrementAndGet() == 2) {
				failedId.set(publicId);
				throw failure;
			}

			return Map.of("secure_url", IMAGE_URL_PREFIX + publicId + ".png");
		});

		beginCallbacks();

		String firstUrl = storage.upload(MediaTestFiles.image("first.png"), "products");

		assertThatThrownBy(() -> storage.upload(MediaTestFiles.image("second.png"), "products"))
				.isInstanceOf(IllegalStateException.class).hasCause(failure);

		assertThat(failedId.get()).startsWith("utetra/products/");
		assertThat(failedId.get()).isNotEqualTo(imagePublicId(firstUrl));

		verify(uploader).destroy(eq(failedId.get()), eq(destroyOptions("image")));

		verify(uploader, never()).destroy(eq(imagePublicId(firstUrl)), anyMap());

		rollbackCallbacks();

		verify(uploader).destroy(eq(imagePublicId(firstUrl)), eq(destroyOptions("image")));

		verify(uploader, times(2)).destroy(anyString(), anyMap());
	}

	@Test
	void anUnexpectedResponseDeletesOnlyTheGeneratedAsset() throws IOException {

		String foreignId = "utetra/products/someone-elses-image";

		AtomicReference<String> generatedId = new AtomicReference<>();

		when(uploader.upload(any(byte[].class), anyMap())).thenAnswer(invocation -> {
			Map<?, ?> options = invocation.getArgument(1);
			generatedId.set((String) options.get("public_id"));

			return Map.of("secure_url", IMAGE_URL_PREFIX + foreignId + ".png");
		});

		assertThatThrownBy(() -> storage.upload(MediaTestFiles.image("cover.png"), "products"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên Cloudinary.");

		assertThat(generatedId.get()).startsWith("utetra/products/");

		verify(uploader).destroy(eq(generatedId.get()), eq(destroyOptions("image")));

		verify(uploader, never()).destroy(eq(foreignId), anyMap());

		verify(uploader, times(1)).destroy(anyString(), anyMap());
	}

	@Test
	void cleanupFailureDoesNotHideTheOriginalUploadFailure() throws IOException {

		IOException uploadFailure = new IOException("Upload interrupted");
		IOException cleanupFailure = new IOException("Destroy unavailable");

		when(uploader.upload(any(byte[].class), anyMap())).thenThrow(uploadFailure);

		when(uploader.destroy(anyString(), anyMap())).thenThrow(cleanupFailure);

		assertThatThrownBy(() -> storage.upload(MediaTestFiles.image("cover.png"), "products"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Không thể tải file lên Cloudinary.")
				.hasCause(uploadFailure);

		verify(uploader, times(1)).destroy(anyString(), eq(destroyOptions("image")));
	}

	@Test
	void rollbackCleanupFailureDoesNotEscapeTheCompletionCallback() throws IOException {

		beginCallbacks();

		String url = storage.upload(MediaTestFiles.image("cover.png"), "products");

		when(uploader.destroy(anyString(), anyMap())).thenThrow(new IOException("Destroy unavailable"));

		assertThatCode(this::rollbackCallbacks).doesNotThrowAnyException();

		verify(uploader).destroy(eq(imagePublicId(url)), eq(destroyOptions("image")));
	}

	@Test
	void videoDeletionAfterCommitUsesVideoResourceType() throws IOException {

		String publicId = "utetra/reviews/old-video";

		String url = "https://res.cloudinary.com/" + CLOUD_NAME + "/video/upload/v123/" + publicId + ".mp4";

		beginCallbacks();

		storage.deleteAfterCommit(url);

		verify(uploader, never()).destroy(anyString(), anyMap());

		commitCallbacks();

		verify(uploader).destroy(eq(publicId), eq(destroyOptions("video")));

		verify(uploader, times(1)).destroy(anyString(), anyMap());
	}

	private String imagePublicId(String url) {
		assertThat(url).startsWith(IMAGE_URL_PREFIX).endsWith(".png");

		return url.substring(IMAGE_URL_PREFIX.length(), url.length() - ".png".length());
	}

	private Map<String, Object> destroyOptions(String resourceType) {
		return Map.of("resource_type", resourceType, "invalidate", true);
	}

	/*
	 * Giả lập callback của Spring để kiểm thử SDK. Giao dịch H2 được kiểm tra riêng
	 * trong MediaStorageLifecycleTests.
	 */
	private void beginCallbacks() {
		assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
	}

	private void commitCallbacks() {
		List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();

		try {
			callbacks.forEach(TransactionSynchronization::afterCommit);

			callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
		} finally {
			clearTransactionState();
		}
	}

	private void rollbackCallbacks() {
		List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();

		try {
			callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
		} finally {
			clearTransactionState();
		}
	}
}