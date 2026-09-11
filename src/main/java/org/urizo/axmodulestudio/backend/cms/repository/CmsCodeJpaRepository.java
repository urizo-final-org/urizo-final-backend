package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsCodeEntity;

public interface CmsCodeJpaRepository extends JpaRepository<CmsCodeEntity, Long> {
    List<CmsCodeEntity> findAllByOrderByDisplayOrderAscIdAsc();
    boolean existsByGroupKeyAndValue(String groupKey, String value);
}
