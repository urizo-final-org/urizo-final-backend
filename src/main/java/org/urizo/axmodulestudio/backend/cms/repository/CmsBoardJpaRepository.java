package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsBoardEntity;

public interface CmsBoardJpaRepository extends JpaRepository<CmsBoardEntity, Long> {
    List<CmsBoardEntity> findAllByDeletedYnOrderByCreatedAtAsc(String deletedYn);

    Optional<CmsBoardEntity> findByBoardIdAndDeletedYn(Long boardId, String deletedYn);

    Optional<CmsBoardEntity> findFirstByBoardNameAndDeletedYnOrderByBoardIdAsc(
            String boardName, String deletedYn);
}
