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
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;

/**
 * 컨텐츠 본문에 넣는 이미지. 바이트를 DB에 담는다.
 *
 * <p>본문에는 바이트가 아니라 조회 주소만 들어가므로 본문 길이에 거의 영향을 주지 않는다.
 * 컨텐츠를 지워도 이 행은 남긴다. 컨텐츠가 소프트 삭제라 되살릴 여지가 있는데 이미지를 먼저
 * 지우면 짝이 맞지 않는다.
 */
@Entity
@Table(name = "cms_content_image", schema = "app")
public class CmsContentImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id", nullable = false, updatable = false)
    private Long imageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private AdminAccountEntity author;

    @Column(name = "content_type", nullable = false, length = 40, updatable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private int byteSize;

    /**
     * {@code @Lob}을 붙이지 않는다. 붙이면 Hibernate가 Postgres large object({@code oid})를
     * 기대해 {@code bytea} 컬럼과 어긋나고 스키마 검증이 기동을 막는다.
     */
    @Column(name = "bytes", nullable = false, updatable = false)
    private byte[] bytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CmsContentImageEntity() {
    }

    public CmsContentImageEntity(
            AdminAccountEntity author, String contentType, byte[] bytes, Instant now) {
        this.author = author;
        this.contentType = contentType;
        this.bytes = bytes;
        this.byteSize = bytes.length;
        this.createdAt = now;
    }

    public Long getImageId() {
        return imageId;
    }

    public String getContentType() {
        return contentType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
