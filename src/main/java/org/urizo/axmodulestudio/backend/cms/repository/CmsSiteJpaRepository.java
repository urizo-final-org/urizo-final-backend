package org.urizo.axmodulestudio.backend.cms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsSiteEntity;

public interface CmsSiteJpaRepository extends JpaRepository<CmsSiteEntity, String> {
    List<CmsSiteEntity> findAllByOrderBySiteKeyAsc();

    List<CmsSiteEntity> findAllByEnabledYn(String enabledYn);

    List<CmsSiteEntity> findAllByDefaultYn(String defaultYn);

    Optional<CmsSiteEntity> findFirstByDefaultYn(String defaultYn);

    Optional<CmsSiteEntity> findFirstByPublicPath(String publicPath);
}
