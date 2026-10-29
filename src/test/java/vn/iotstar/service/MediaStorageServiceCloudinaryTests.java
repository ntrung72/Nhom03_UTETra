package vn.iotstar.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MediaStorageServiceCloudinaryTests {
    @Test
    void rollbackDeletesCloudinaryUploadUsingItsPublicId() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenAnswer(invocation -> {
            Map<?, ?> options = invocation.getArgument(1);
            return Map.of("secure_url", "https://res.cloudinary.com/demo/image/upload/v123/"
                + options.get("public_id") + ".png");
        });
        when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
        MediaStorageService storage = storage(cloudinary);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            storage.upload(image(), "avatars");
            verify(uploader, never()).destroy(anyString(), anyMap());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(uploader).destroy(startsWith("utetra/avatars/"), anyMap());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void oldAssetIsDeletedAfterCommitAndForeignUrlIsIgnored() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
        MediaStorageService storage = storage(cloudinary);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            storage.deleteAfterCommit("https://res.cloudinary.com/demo/image/upload/v123/utetra/avatars/old-id.png");
            storage.deleteAfterCommit("https://res.cloudinary.com/other/image/upload/v123/utetra/avatars/foreign.png");
            storage.deleteAfterCommit("https://example.com/utetra/avatars/foreign.png");
            verify(uploader, never()).destroy(anyString(), anyMap());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(uploader).destroy(eq("utetra/avatars/old-id"), anyMap());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedCloudinaryUploadAttemptsToRemovePartiallyCreatedAsset() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenThrow(new IOException("network interrupted"));
        when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));

        assertThrows(IllegalStateException.class, () -> storage(cloudinary).upload(image(), "avatars"));
        verify(uploader).destroy(startsWith("utetra/avatars/"), anyMap());
    }

    private MediaStorageService storage(Cloudinary cloudinary) {
        return new MediaStorageService(new MediaValidationService(), cloudinary, "demo", Path.of("target/cloudinary-test"));
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "avatar.png", "image/png", new byte[] {1, 2, 3});
    }
}
