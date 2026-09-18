package org.urizo.axmodulestudio.backend.integration.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputOptimizationScopeTest {
    @Test void restoresContextAndReplacesFinalBudgetOnFallbackWithoutLosingOriginalProcessingIdentity() {
        var retained = new InputOptimizationScope.Decision("call", "read_file", "RETENTION", "extra_retained", 500, 500, false);
        var elided = new InputOptimizationScope.Decision("call", "read_file", "REQUEST_BUDGET", "elided", 500, 50, true);
        try (var scope = new InputOptimizationScope(false, true, List.of(retained))) {
            var id = InputOptimizationScope.current().processingId();
            InputOptimizationScope.finalBudget(List.of(elided));
            assertThat(InputOptimizationScope.current().decisions()).containsExactly(retained, elided);
            InputOptimizationScope.finalBudget(List.of());
            assertThat(InputOptimizationScope.current().processingId()).isEqualTo(id);
            assertThat(InputOptimizationScope.current().decisions()).containsExactly(retained);
            try (var inner = new InputOptimizationScope(true, false, List.of())) {
                assertThat(InputOptimizationScope.current().processingId()).isNotEqualTo(id);
            }
            assertThat(InputOptimizationScope.current().processingId()).isEqualTo(id);
        }
        assertThat(InputOptimizationScope.current()).isNull();
    }
}
