package org.urizo.axmodulestudio.backend.cms.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsContentImageEntity;

public interface CmsContentImageJpaRepository
        extends JpaRepository<CmsContentImageEntity, Long> {
}
