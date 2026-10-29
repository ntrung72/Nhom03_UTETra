package vn.iotstar.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.OtpToken;

public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OtpToken> findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(String email, OtpPurpose purpose);
    Optional<OtpToken> findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(String email);
    long countByEmailIgnoreCaseAndCreatedAtGreaterThanEqual(String email, LocalDateTime createdAt);
}
