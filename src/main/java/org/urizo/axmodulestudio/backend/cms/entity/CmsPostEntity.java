package org.urizo.axmodulestudio.backend.cms.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;

@Entity
@Table(name = "cms_post", schema = "app")
public class CmsPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false, updatable = false)
    private CmsBoardEntity board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private AdminAccountEntity author;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "deleted_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    private String deletedYn;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CmsPostEntity() {
    }

    public CmsPostEntity(
            CmsBoardEntity board,
            AdminAccountEntity author,
            String title,
            String body,
            Instant now) {
        this.board = board;
        this.author = author;
        this.title = title;
        this.body = body;
        this.createdAt = now;
        this.updatedAt = now;
        this.deletedYn = "N";
    }

    public Long getPostId() {
        return postId;
    }

    public CmsBoardEntity getBoard() {
        return board;
    }

    public AdminAccountEntity getAuthor() {
        return author;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void change(String title, String body, Instant changedAt) {
        this.title = title;
        this.body = body;
        this.updatedAt = changedAt;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedYn = "Y";
        this.deletedAt = deletedAt;
        this.updatedAt = deletedAt;
    }
}
