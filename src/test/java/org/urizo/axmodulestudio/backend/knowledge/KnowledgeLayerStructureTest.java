package org.urizo.axmodulestudio.backend.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.knowledge.controller.ProductApiController;
import org.urizo.axmodulestudio.backend.knowledge.repository.ConnectorStore;
import org.urizo.axmodulestudio.backend.knowledge.repository.KnowledgeStore;
import org.urizo.axmodulestudio.backend.knowledge.repository.ProductJobStore;
import org.urizo.axmodulestudio.backend.knowledge.repository.ProductStore;
import org.urizo.axmodulestudio.backend.knowledge.repository.ProjectStore;
import org.urizo.axmodulestudio.backend.knowledge.repository.RagStore;
import org.urizo.axmodulestudio.backend.knowledge.service.ConnectorOperations;
import org.urizo.axmodulestudio.backend.knowledge.service.KnowledgeOperations;
import org.urizo.axmodulestudio.backend.knowledge.service.ProductJobOperations;
import org.urizo.axmodulestudio.backend.knowledge.service.ProductService;
import org.urizo.axmodulestudio.backend.knowledge.service.ProjectOperations;
import org.urizo.axmodulestudio.backend.knowledge.service.RagOperations;

class KnowledgeLayerStructureTest {

    @Test
    void controllerRoutesOnlyThroughDomainOperationBoundaries() {
        assertThat(onlyConstructor(ProductApiController.class).getParameterTypes())
                .containsExactly(
                        ProjectOperations.class,
                        ConnectorOperations.class,
                        KnowledgeOperations.class,
                        RagOperations.class,
                        ProductJobOperations.class);
    }

    @Test
    void serviceImplementsAllExistingKnowledgeOperations() {
        assertThat(ProductService.class.getInterfaces())
                .containsExactly(
                        ProjectOperations.class,
                        ConnectorOperations.class,
                        KnowledgeOperations.class,
                        RagOperations.class,
                        ProductJobOperations.class);
    }

    @Test
    void repositoryFacadeKeepsOnlyInfrastructureAndKnowledgeRepositories() {
        assertThat(onlyConstructor(ProductStore.class).getParameterTypes())
                .containsExactly(
                        JdbcTemplate.class,
                        TransactionTemplate.class,
                        ObjectMapper.class,
                        ProjectStore.class,
                        ConnectorStore.class,
                        KnowledgeStore.class,
                        RagStore.class,
                        ProductJobStore.class);
    }

    private static Constructor<?> onlyConstructor(Class<?> type) {
        assertThat(type.getDeclaredConstructors()).hasSize(1);
        return type.getDeclaredConstructors()[0];
    }
}
