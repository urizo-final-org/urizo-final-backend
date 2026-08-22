package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsMenuEntity;

public interface CmsMenuJpaRepository extends JpaRepository<CmsMenuEntity, Long> {
    List<CmsMenuEntity> findAllByOrderByDisplayOrderAscMenuIdAsc();

    List<CmsMenuEntity> findAllByTargetTypeAndTargetId(String targetType, Long targetId);

    List<CmsMenuEntity> findAllByPath(String path);
}
