package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.urizo.axmodulestudio.backend.cms.entity.CmsTemplateEntity;

public interface CmsTemplateJpaRepository extends JpaRepository<CmsTemplateEntity, String> {
    List<CmsTemplateEntity> findAllByOrderByTemplateKeyAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from CmsTemplateEntity template where template.templateKey = :key")
    Optional<CmsTemplateEntity> findForUpdate(@Param("key") String key);
}
