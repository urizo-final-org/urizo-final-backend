package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsCodeGroupEntity;

public interface CmsCodeGroupJpaRepository extends JpaRepository<CmsCodeGroupEntity, String> {
    List<CmsCodeGroupEntity> findAllByOrderByDisplayOrderAscKeyAsc();
}
