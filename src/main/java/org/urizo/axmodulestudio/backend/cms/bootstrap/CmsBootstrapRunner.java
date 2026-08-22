package org.urizo.axmodulestudio.backend.cms.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

@Component
@Profile("local-full")
@Order(20)
public class CmsBootstrapRunner implements ApplicationRunner {

    private final CmsService cms;

    public CmsBootstrapRunner(CmsService cms) {
        this.cms = cms;
    }

    @Override
    public void run(ApplicationArguments args) {
        cms.ensureDemoData();
    }
}
