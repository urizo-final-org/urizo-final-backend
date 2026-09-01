package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailSelectionContract;

class GuardrailPathSelectionServiceTest {

    private static final String DENIED_BACKEND =
            "src/main/java/org/urizo/axmodulestudio/backend/auth";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final GuardrailPathSelectionService service =
            new GuardrailPathSelectionService(jdbc, transactions);

    private static GuardrailSelectionContract.SaveRequest save(
            String repository, GuardrailSelectionContract.Selection... selections) {
        return new GuardrailSelectionContract.SaveRequest(repository, List.of(selections));
    }

    private static GuardrailSelectionContract.Selection on(String path) {
        return new GuardrailSelectionContract.Selection(path, true, null);
    }

    @Test
    @DisplayName("A fixed Denylist path cannot be stored, so no saved choice can grant it")
    void refusesADeniedPath() {
        assertThatThrownBy(() -> service.save(save("backend", on(DENIED_BACKEND))))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("cannot be selected");

        verify(transactions, never()).executeWithoutResult(any());
    }

    @Test
    @DisplayName("A denied path is refused even when it rides along with allowed ones")
    void refusesTheWholeSaveWhenOneEntryIsDenied() {
        assertThatThrownBy(() -> service.save(save("backend",
                on("src/main/java/org/urizo/axmodulestudio/backend/cms"),
                on(DENIED_BACKEND))))
                .isInstanceOf(CodingWorkerException.class);

        verify(transactions, never()).executeWithoutResult(any());
    }

    @Test
    @DisplayName("A path shaped differently from what the scan reports is refused")
    void refusesAMisshapenPath() {
        for (String path : List.of(
                "/src/features/cms", "src/features/cms/", "src\\features\\cms",
                "src/features/../auth", "")) {
            assertThatThrownBy(() -> service.save(save("frontend", on(path))))
                    .isInstanceOf(CodingWorkerException.class);
        }
        verify(transactions, never()).executeWithoutResult(any());
    }

    @Test
    @DisplayName("The same path twice is refused rather than silently collapsed")
    void refusesADuplicatePath() {
        assertThatThrownBy(() -> service.save(save("frontend",
                on("src/features/cms"), on("src/features/cms"))))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    @DisplayName("An unregistered repository is refused before anything is written")
    void refusesAnUnknownRepository() {
        assertThatThrownBy(() -> service.save(save("master", on("docs"))))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("not registered");

        assertThatThrownBy(() -> service.selections("master"))
                .isInstanceOf(CodingWorkerException.class);
        verify(jdbc, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
    }

    @Test
    @DisplayName("Allowed product folders are accepted")
    void acceptsProductFolders() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object.class))).thenReturn(List.of());

        service.save(save("frontend",
                new GuardrailSelectionContract.Selection("src/features/cms", true, "CMS 화면"),
                new GuardrailSelectionContract.Selection("src/features/site", false, null)));

        verify(transactions).executeWithoutResult(any());
    }

    @Test
    @DisplayName("A blank label is stored as none rather than as an empty string")
    void treatsABlankLabelAsAbsent() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object.class))).thenReturn(List.of());

        service.save(save("frontend",
                new GuardrailSelectionContract.Selection("src/features/cms", true, "   ")));

        verify(transactions).executeWithoutResult(any());
    }
}
