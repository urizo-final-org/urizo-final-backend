package org.urizo.axmodulestudio.backend.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.connector.ConnectorOperations;
import org.urizo.axmodulestudio.backend.connector.ConnectorStore;
import org.urizo.axmodulestudio.backend.job.ProductJobOperations;
import org.urizo.axmodulestudio.backend.job.ProductJobStore;
import org.urizo.axmodulestudio.backend.knowledge.KnowledgeOperations;
import org.urizo.axmodulestudio.backend.knowledge.KnowledgeStore;
import org.urizo.axmodulestudio.backend.project.ProjectOperations;
import org.urizo.axmodulestudio.backend.project.ProjectStore;
import org.urizo.axmodulestudio.backend.rag.RagOperations;
import org.urizo.axmodulestudio.backend.rag.RagStore;

class ProductDomainSeamTest {

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
    void serviceKeepsTheLegacyProductFacadeBehindAllDomainOperations() {
        assertThat(ProductService.class.getInterfaces())
                .containsExactly(
                        ProjectOperations.class,
                        ConnectorOperations.class,
                        KnowledgeOperations.class,
                        RagOperations.class,
                        ProductJobOperations.class);
    }

    @Test
    void productStoreKeepsOnlyInfrastructureAndFeatureLocalStoreDependencies() {
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
