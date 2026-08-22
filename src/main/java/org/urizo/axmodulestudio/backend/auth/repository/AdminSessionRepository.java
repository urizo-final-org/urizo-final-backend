package org.urizo.axmodulestudio.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.urizo.axmodulestudio.backend.auth.entity.AdminSessionEntity;
import org.urizo.axmodulestudio.backend.auth.entity.RefreshTokenStatus;

public interface AdminSessionRepository extends JpaRepository<AdminSessionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AdminSessionEntity session where session.jwtId = :jwtId")
    Optional<AdminSessionEntity> findByJwtIdForUpdate(@Param("jwtId") UUID jwtId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AdminSessionEntity session
               set session.status = :revoked,
                   session.revokedAt = :revokedAt
             where session.accountId = :accountId
               and session.status = :active
            """)
    int revokeActiveByAccountId(
            @Param("accountId") UUID accountId,
            @Param("revokedAt") Instant revokedAt,
            @Param("active") RefreshTokenStatus active,
            @Param("revoked") RefreshTokenStatus revoked);
}
