package vn.iotstar.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ReturnRequestMediaRepository;

class PrivateMediaMigrationTests {


    @Test
    void localLegacyFileIsPrivateAndRejectsTraversal() throws Exception {
        Path temp = Files.createTempDirectory("step37-migration-");
        Path publicDir = temp.resolve("public");
        Path privateDir = temp.resolve("private");
        Files.createDirectories(publicDir.resolve("returns"));
        Files.writeString(publicDir.resolve("returns/old.png"), "example legacy bytes");
        var migration = new PrivateMediaMigrationService(mock(OrderRepository.class),
            mock(ReturnRequestMediaRepository.class), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(migration, "uploadDir", publicDir.toString());
        ReflectionTestUtils.setField(migration, "privateUploadDir", privateDir.toString());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            String migrated = migration.copyLocalLegacy("/uploads/returns/old.png", "returns");
            assertTrue(migrated.startsWith("private:returns/"));
            assertEquals(migrated, migration.copyLocalLegacy(migrated, "returns"));
            assertTrue(Files.exists(privateDir.resolve(migrated.substring("private:".length()))));
            assertTrue(Files.exists(publicDir.resolve("returns/old.png")));
            assertThrows(IllegalArgumentException.class,
                () -> migration.copyLocalLegacy("/uploads/returns/../escape.png", "returns"));
            assertThrows(IllegalArgumentException.class,
                () -> migration.copyLocalLegacy("https://example.org/proof.png", "returns"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
