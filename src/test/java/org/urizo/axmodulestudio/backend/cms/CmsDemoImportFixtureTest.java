package org.urizo.axmodulestudio.backend.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.cms.controller.CmsAdminController;
import org.urizo.axmodulestudio.backend.cms.controller.CmsCodeController;
import org.urizo.axmodulestudio.backend.cms.controller.CmsSiteSettingsController;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;
import org.urizo.axmodulestudio.backend.cms.repository.CmsSiteRepository;
import org.urizo.axmodulestudio.backend.cms.service.CmsCodeService;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;
import org.urizo.axmodulestudio.backend.cms.service.CmsSiteSettingsService;
import org.urizo.axmodulestudio.backend.governance.CmsChangeRecorder;

/** Opt-in only: real CMS controllers/services/JPA against an independently migrated fixture DB.
 * Authentication is a test principal; this does not claim a live login/security-filter test. */
@EnabledIfEnvironmentVariable(named = "AXMS_CMS_IMPORT_FIXTURE", matches = "true")
class CmsDemoImportFixtureTest {
    static final UUID ACTOR = UUID.fromString("8b9a0071-6f0d-4f63-bca9-422e074ed148");

    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {
            "org.urizo.axmodulestudio.backend.cms.repository",
            "org.urizo.axmodulestudio.backend.auth.repository"},
            entityManagerFactoryRef = "authEntityManagerFactory",
            transactionManagerRef = "authJpaTransactionManager")
    @Import({CmsRepository.class, CmsSiteRepository.class, CmsService.class,
            CmsCodeService.class, CmsSiteSettingsService.class, CmsChangeRecorder.class,
            CmsAdminController.class, CmsCodeController.class, CmsSiteSettingsController.class})
    static class FixtureConfig {
        @Bean DataSource productDataSource() {
            String url = System.getenv("AXMS_CMS_IMPORT_FIXTURE_JDBC");
            if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/axms_cms_import_fixture")) {
                throw new IllegalArgumentException("An isolated loopback fixture URL is required.");
            }
            return new DriverManagerDataSource(url, "cms_app", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean authEntityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("org.urizo.axmodulestudio.backend.cms.entity",
                    "org.urizo.axmodulestudio.backend.auth.entity");
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.default_schema", "app", "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }

        @Bean JpaTransactionManager authJpaTransactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean JdbcTemplate productJdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean AuthService authService() {
            AuthService auth = mock(AuthService.class);
            when(auth.loadActor(ACTOR)).thenReturn(new AuthenticatedActor(ACTOR, "Fixture recipient", AdminRole.SUPER_ADMIN));
            return auth;
        }
    }

    @Test
    void importerUsesRealCmsValidationPersistenceAndHistoryWithoutTouchingOtherData() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("local-full");
            context.register(FixtureConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> forward(mvc, exchange));
            server.start();
            try {
                String python = System.getenv("AXMS_CMS_IMPORT_FIXTURE_PYTHON");
                assertThat(python).isNotBlank();
                var process = new ProcessBuilder(python, "scripts/cms-demo/verify_fixture.py",
                        "--base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "--container", System.getenv("AXMS_CMS_IMPORT_FIXTURE_CONTAINER"))
                        .directory(Path.of(".").toFile()).inheritIO().start();
                assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
                assertThat(process.exitValue()).isZero();
            } finally {
                server.stop(0);
            }
        }
    }

    private static void forward(MockMvc mvc, HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            MockHttpServletRequestBuilder request;
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                int start = indexOf(body, marker) + marker.length;
                byte[] ending = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
                int end = indexOf(body, ending);
                if (start < marker.length || end < start) throw new IllegalArgumentException("Bad fixture multipart");
                request = MockMvcRequestBuilders.multipart(path).file(new MockMultipartFile(
                        "file", "fixture.png", "image/png", Arrays.copyOfRange(body, start, end)));
            } else {
                request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), path)
                        .content(body);
                if (contentType != null) request.contentType(contentType);
            }
            request.principal(new UsernamePasswordAuthenticationToken(ACTOR.toString(), null, List.of()));
            var response = mvc.perform(request).andReturn().getResponse();
            if (response.getContentType() != null) exchange.getResponseHeaders().set("Content-Type", response.getContentType());
            byte[] result = response.getContentAsByteArray();
            exchange.sendResponseHeaders(response.getStatus(), result.length == 0 ? -1 : result.length);
            exchange.getResponseBody().write(result);
        } catch (Exception failure) {
            // Do not leak request bodies, account material, or DB details to HTTP/log output.
            try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private static int indexOf(byte[] data, byte[] marker) {
        outer: for (int i = 0; i <= data.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) if (data[i + j] != marker[j]) continue outer;
            return i;
        }
        return -1;
    }
}
