package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsTemplateEntity;

public interface CmsTemplateJpaRepository extends JpaRepository<CmsTemplateEntity, String> {
    List<CmsTemplateEntity> findAllByOrderByTemplateKeyAsc();

}
