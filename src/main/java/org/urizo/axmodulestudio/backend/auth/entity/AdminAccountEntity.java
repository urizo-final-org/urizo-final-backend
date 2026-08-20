package org.urizo.axmodulestudio.backend.auth.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "admin_account", schema = "app")
public class AdminAccountEntity {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "login_id", nullable = false, length = 120, unique = true)
    private String loginId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private AdminRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminAccountEntity() {
    }

    public AdminAccountEntity(
            UUID accountId,
            String loginId,
            String displayName,
            String passwordHash,
            AdminRole role,
            AccountStatus status,
            Instant createdAt) {
        this.accountId = Objects.requireNonNull(accountId, "accountId is required.");
        this.loginId = requireText(loginId, "loginId");
        this.displayName = requireText(displayName, "displayName");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role is required.");
        this.status = Objects.requireNonNull(status, "status is required.");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required.");
        this.updatedAt = createdAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AdminRole getRole() {
        return role;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean canAuthenticate() {
        return status.canAuthenticate();
    }

    public void changeStatus(AccountStatus nextStatus, Instant changedAt) {
        this.status = Objects.requireNonNull(nextStatus, "nextStatus is required.");
        this.updatedAt = Objects.requireNonNull(changedAt, "changedAt is required.");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }
}
