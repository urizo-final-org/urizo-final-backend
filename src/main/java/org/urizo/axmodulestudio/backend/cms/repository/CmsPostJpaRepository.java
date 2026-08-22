package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsPostEntity;

public interface CmsPostJpaRepository extends JpaRepository<CmsPostEntity, Long> {
    List<CmsPostEntity> findAllByBoard_BoardIdAndDeletedYnOrderByCreatedAtDesc(
            Long boardId, String deletedYn);

    Optional<CmsPostEntity> findByPostIdAndDeletedYn(Long postId, String deletedYn);

    boolean existsByBoard_BoardIdAndTitleAndDeletedYn(
            Long boardId, String title, String deletedYn);
}
