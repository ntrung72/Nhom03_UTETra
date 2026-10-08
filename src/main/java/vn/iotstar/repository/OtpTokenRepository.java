package vn.iotstar.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.OtpToken;

public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {
    Optional<OtpToken> findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(String email, OtpPurpose purpose);
    Optional<OtpToken> findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(String email);
    long countByEmailIgnoreCaseAndCreatedAtGreaterThanEqual(String email, LocalDateTime createdAt);
}
