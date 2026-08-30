package org.urizo.axmodulestudio.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.urizo.axmodulestudio.backend.coding.controller.CodingModelTurnController;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.coding.controller.CodingJobLifecycleController;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleService;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;

@SpringBootTest
class Stage1ConfigurationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void databaseMutatingBootstrapIsDisabledInTheStage1DefaultProfile() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.batch.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(environment.getProperty("spring.batch.job.enabled")).isEqualTo("false");
    }

    @Test
    void modelTurnBridgeIsDisabledAndHasNoEndpointBeansByDefault() {
        assertThat(environment.getProperty("ax.coding.model-turn-bridge.enabled")).isEqualTo("false");
        assertThat(applicationContext.getBeansOfType(CodingModelTurnController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(CodingModelTurnService.class)).isEmpty();
    }

    @Test
    void codingJobLifecycleIsDisabledAndHasNoEndpointBeansByDefault() {
        assertThat(environment.getProperty("ax.coding.job-lifecycle.enabled")).isEqualTo("false");
        assertThat(applicationContext.getBeansOfType(CodingJobLifecycleController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(CodingJobLifecycleService.class)).isEmpty();
    }

    @Test
    void mcpPlatformAndAutomaticToolCallbacksAreDisabledByDefault() {
        assertThat(environment.getProperty("ax.ai.mcp-platform.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.ai.mcp.client.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.ai.mcp.client.toolcallback.enabled")).isEqualTo("false");
        assertThat(applicationContext.getBeansOfType(McpPlatformClient.class)).isEmpty();
    }
}
