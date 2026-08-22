package org.urizo.axmodulestudio.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;

public interface AdminAccountRepository extends JpaRepository<AdminAccountEntity, UUID> {
    Optional<AdminAccountEntity> findByLoginId(String loginId);

    /** Non-managed credential view so password work completes before taking the account lock. */
    @Query("""
            select account.accountId as accountId,
                   account.passwordHash as passwordHash
              from AdminAccountEntity account
             where account.loginId = :loginId
            """)
    Optional<LoginCredential> findCredentialByLoginId(@Param("loginId") String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from AdminAccountEntity account where account.accountId = :accountId")
    Optional<AdminAccountEntity> findByIdForUpdate(@Param("accountId") UUID accountId);

    boolean existsByLoginId(String loginId);

    interface LoginCredential {
        UUID getAccountId();
        String getPasswordHash();
    }
}
