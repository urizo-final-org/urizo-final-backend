package org.urizo.axmodulestudio.backend.cms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.cms.controller.*;
import org.urizo.axmodulestudio.backend.cms.repository.*;
import org.urizo.axmodulestudio.backend.cms.service.*;
import org.urizo.axmodulestudio.backend.governance.CmsChangeRecorder;
import org.urizo.axmodulestudio.backend.integration.persistence.JpaConfig;

/** Real controllers, JPA and history on an isolated migrated DB; authentication is mocked. */
@EnabledIfEnvironmentVariable(named = "AXMS_TEMPLATE_IMAGES_FIXTURE", matches = "true")
class CmsTemplateImagesFixtureTest {
    static final UUID ACTOR = UUID.fromString("8b9a0071-6f0d-4f63-bca9-422e074ed148");
    final ObjectMapper json = new ObjectMapper();

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @Import({JpaConfig.class, CmsRepository.class, CmsSiteRepository.class, CmsService.class,
            CmsCodeService.class, CmsSiteSettingsService.class, CmsChangeRecorder.class,
            CmsAdminController.class, CmsSiteController.class})
    static class FixtureConfig {
        @Bean DataSource productDataSource() {
            String url = System.getenv("AXMS_TEMPLATE_IMAGES_FIXTURE_JDBC");
            if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/axms_template_fixture")) {
                throw new IllegalArgumentException("An isolated loopback fixture URL is required.");
            }
            return new DriverManagerDataSource(url, "cms_app", "");
        }
        @Bean JdbcTemplate productJdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean Clock clock() {
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(Instant.parse("2026-09-11T12:00:00Z"));
            return clock;
        }
        @Bean AuthService authService() {
            AuthService auth = mock(AuthService.class);
            when(auth.loadActor(ACTOR)).thenReturn(new AuthenticatedActor(ACTOR, "Fixture", AdminRole.SUPER_ADMIN));
            return auth;
        }
    }

    @Test
    void orderedImagesRoundTripLegacyWritesAndHistoryRollback() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("local-full");
            context.register(FixtureConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            ObjectNode template = (ObjectNode) json.readTree(mvc.perform(get("/api/site/context"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("template");
            String key = template.get("key").asText();
            String legacy = template.get("heroImageUrl").asText();
            assertThat(template.get("heroImageUrls").get(0).asText()).isEqualTo(legacy);
            long beforeHistory = historyCount(jdbc);
            for (int count = 0; count <= 5; count++) {
                List<String> images = IntStream.range(0, count).mapToObj(i -> "/fixture-" + i + ".png").toList();
                template.remove("heroImages");
                template.set("heroImageUrls", json.valueToTree(images));
                template.put("heroImageUrl", "/stale-scalar.png");
                template = save(mvc, key, template);
                assertThat(template.get("heroImageUrls")).isEqualTo(json.valueToTree(images));
                assertThat(template.get("heroImageUrl").asText()).isEqualTo(count == 0 ? "" : images.get(0));
                var published = json.readTree(mvc.perform(get("/api/site/context"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(published.at("/template/heroImageUrls")).isEqualTo(json.valueToTree(images));
            }
            assertThat(historyCount(jdbc)).isEqualTo(beforeHistory + 6);
            var ordered = json.valueToTree(List.of("/last.png", "/first.png"));
            template.remove("heroImages");
            template.set("heroImageUrls", ordered);
            template = save(mvc, key, template);
            assertThat(template.get("heroImageUrls")).isEqualTo(ordered);
            template.remove("heroImageUrls");
            template.remove("heroImages");
            template.put("heroTitle", "Legacy text update");
            template = save(mvc, key, template);
            assertThat(template.get("heroImageUrls")).isEqualTo(ordered);
            template.remove("heroImageUrls");
            template.remove("heroImages");
            template.put("heroImageUrl", "/replacement.png");
            template = save(mvc, key, template);
            assertThat(template.get("heroImageUrls")).isEqualTo(json.valueToTree(List.of("/replacement.png", "/first.png")));

            var captions = json.readTree("[{\"url\":\"/sea.png\",\"title\":\"바다로\",\"description\":\"오늘의 여행\"},{\"url\":\"/forest.png\",\"title\":\"숲길\",\"description\":\"느린 산책\"}]");
            template.set("heroImages", captions);
            template = save(mvc, key, template);
            assertThat(template.get("heroImages")).isEqualTo(captions);
            assertThat(json.readTree(mvc.perform(get("/api/site/context")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).at("/template/heroImages")).isEqualTo(captions);
            assertThat(json.readTree(jdbc.queryForObject("SELECT hero_images::text FROM app.cms_template WHERE template_key=?", String.class, key))).isEqualTo(captions);
            template.remove("heroImages");
            template.set("heroImageUrls", json.valueToTree(List.of("/forest.png", "/sea.png")));
            template = save(mvc, key, template);
            assertThat(template.at("/heroImages/0/title").asText()).isEqualTo("숲길");
            assertThat(template.at("/heroImages/1/description").asText()).isEqualTo("오늘의 여행");

            long validHistory = historyCount(jdbc);
            ObjectNode invalid = template.deepCopy();
            invalid.remove("heroImages");
            invalid.set("heroImageUrls", json.valueToTree(java.util.Collections.nCopies(6, "/six.png")));
            mvc.perform(put("/api/cms/templates/" + key).principal(principal())
                    .contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
            assertThat(historyCount(jdbc)).isEqualTo(validHistory);
            String saved = jdbc.queryForObject("SELECT hero_image_urls::text FROM app.cms_template WHERE template_key=?", String.class, key);
            String savedCaptions = jdbc.queryForObject("SELECT hero_images::text FROM app.cms_template WHERE template_key=?", String.class, key);
            when(context.getBean(Clock.class).instant()).thenThrow(new IllegalStateException("fixture history failure"));
            template.set("heroImages", json.readTree("[{\"url\":\"/rollback.png\",\"title\":\"Rollback\",\"description\":\"Must not persist\"}]"));
            ObjectNode failing = template;
            assertThatThrownBy(() -> save(mvc, key, failing)).hasRootCauseMessage("fixture history failure");
            assertThat(jdbc.queryForObject("SELECT hero_image_urls::text FROM app.cms_template WHERE template_key=?", String.class, key)).isEqualTo(saved);
            assertThat(jdbc.queryForObject("SELECT hero_images::text FROM app.cms_template WHERE template_key=?", String.class, key)).isEqualTo(savedCaptions);
            assertThat(historyCount(jdbc)).isEqualTo(validHistory);
        }
    }

    private ObjectNode save(MockMvc mvc, String key, ObjectNode template) throws Exception {
        return (ObjectNode) json.readTree(mvc.perform(put("/api/cms/templates/" + key).principal(principal())
                .contentType("application/json").content(template.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private static UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken(ACTOR.toString(), null, List.of());
    }
    private static long historyCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM app.cms_change_history WHERE resource_type='TEMPLATE'", Long.class);
    }
}
