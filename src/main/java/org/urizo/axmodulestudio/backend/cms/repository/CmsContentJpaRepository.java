package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsContentEntity;

public interface CmsContentJpaRepository extends JpaRepository<CmsContentEntity, Long> {
    List<CmsContentEntity> findAllByDeletedYnOrderByCreatedAtDesc(String deletedYn);

    Optional<CmsContentEntity> findByContentIdAndDeletedYn(Long contentId, String deletedYn);

    Optional<CmsContentEntity> findFirstByTitleAndDeletedYnOrderByContentIdAsc(
            String title, String deletedYn);
}
