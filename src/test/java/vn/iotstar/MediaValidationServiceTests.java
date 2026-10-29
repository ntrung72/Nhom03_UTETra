package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.service.MediaValidationService;

class MediaValidationServiceTests {
    private final MediaValidationService validator = new MediaValidationService();

    @Test
    void validatesMimeAndMatchingExtensionForEveryCatalogImage() {
        for (String folder : new String[] {"products", "shops", "categories"}) {
            assertEquals(MediaType.IMAGE, validator.validate(file("tea.webp", "image/webp", 3), folder));
            assertThrows(IllegalArgumentException.class,
                () -> validator.validate(file("tea.png", "image/jpeg", 3), folder));
            assertThrows(IllegalArgumentException.class,
                () -> validator.validate(file("tea.mp4", "video/mp4", 3), folder));
            assertThrows(IllegalArgumentException.class,
                () -> validator.validate(file("tea.png", "image/png", 10 * 1024 * 1024 + 1), folder));
        }
    }

    @Test
    void keepsAvatarAndDeliveryProofImageLimits() {
        assertEquals(MediaType.IMAGE, validator.validate(file("avatar.jpg", "image/jpeg", 5 * 1024 * 1024), "avatars"));
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(file("avatar.jpg", "image/jpeg", 5 * 1024 * 1024 + 1), "avatars"));
        assertEquals(MediaType.IMAGE,
            validator.validate(file("proof.png", "image/png", 10 * 1024 * 1024), "delivery-proofs"));
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(file("proof.mp4", "video/mp4", 3), "delivery-proofs"));
    }

    @Test
    void reviewAndReturnLimitEachFileAndRejectSixthFile() {
        for (String folder : new String[] {"reviews", "returns"}) {
            assertEquals(MediaType.VIDEO, validator.validate(file("proof.mp4", "video/mp4", 3), folder));
            assertThrows(IllegalArgumentException.class,
                () -> validator.validate(file("proof.mp4", "video/mp4", 25 * 1024 * 1024 + 1), folder));
            MockMultipartFile[] sixFiles = new MockMultipartFile[6];
            for (int i = 0; i < sixFiles.length; i++) sixFiles[i] = file("proof.png", "image/png", 3);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validateFiles(sixFiles, folder));
            assertTrue(error.getMessage().contains("5"));
        }
    }

    private MockMultipartFile file(String filename, String mime, int size) {
        return new MockMultipartFile("file", filename, mime, new byte[size]);
    }
}
