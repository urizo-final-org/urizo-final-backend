package org.urizo.axmodulestudio.backend.cms.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cms_board", schema = "app")
public class CmsBoardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "board_id", nullable = false, updatable = false)
    private Long boardId;

    @Column(name = "board_name", nullable = false, length = 100)
    private String boardName;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "deleted_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    private String deletedYn;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CmsBoardEntity() {
    }

    public CmsBoardEntity(String boardName, String description, Instant now) {
        this.boardName = boardName;
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
        this.deletedYn = "N";
    }

    public Long getBoardId() {
        return boardId;
    }

    public String getBoardName() {
        return boardName;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void change(String boardName, String description, Instant changedAt) {
        this.boardName = boardName;
        this.description = description;
        this.updatedAt = changedAt;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedYn = "Y";
        this.deletedAt = deletedAt;
        this.updatedAt = deletedAt;
    }
}
