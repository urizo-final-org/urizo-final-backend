package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class NaturalCmsStageControllerTest {

    @Test
    void strictQueueJobIdResolvesTheCurrentJobWithoutGuessingItsAttempt() {
        NaturalCmsStore store = mock(NaturalCmsStore.class);
        NaturalCmsStageService stages = mock(NaturalCmsStageService.class);
        NaturalCmsStageController controller = new NaturalCmsStageController(store, stages);
        UUID jobId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        NaturalCmsContract.JobResponse expected = mock(NaturalCmsContract.JobResponse.class);
        when(store.get("Bearer worker", jobId)).thenReturn(expected);

        NaturalCmsContract.JobResponse actual = controller.getCurrent(
                jobId, "Bearer worker");

        assertThat(actual).isSameAs(expected);
        verify(store).get("Bearer worker", jobId);
    }
}
