package org.urizo.axmodulestudio.backend.coding.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.security.JwtTokenProvider;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingConsoleService;

/**
 * The one promise worth pinning: a general administrator's response carries no code.
 * Hiding the diff in the browser would leave it in the network tab, so it must not be sent.
 */
@WebMvcTest(
        controllers = CodingConsoleController.class,
        properties = "ax.coding.job-lifecycle.enabled=true")
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class CodingConsoleControllerTest {

    private static final UUID JOB = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ACCESS_TOKEN = "coding-console-test-token";
    private static final String BASE_SHA = "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHANGED_PATH = "src/main/java/org/urizo/Member.java";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodingConsoleService service;

    @MockitoBean
    private AuthService authService;

    @MockitoBean(name = "authJwtSigningKey")
    private SecretKey authJwtSigningKey;

    @MockitoBean
    private JwtEncoder jwtEncoder;

    @MockitoBean(name = "accessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockitoBean(name = "refreshJwtDecoder")
    private JwtDecoder refreshJwtDecoder;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private JwtProperties jwtProperties;

    @Test
    void aGeneralAdministratorReadsThePlanAndTheReportButNeverTheCode() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        when(service.detail(eq(JOB), eq(AdminRole.GENERAL_ADMIN))).thenReturn(detail(null));

        mockMvc.perform(get("/api/admin/coding/jobs/{jobId}", JOB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.summary").value("회원 목록에 가입일 칸을 더합니다."))
                .andExpect(jsonPath("$.plan.acceptanceCriteria[0]").value("목록에 가입일이 보인다"))
                .andExpect(jsonPath("$.report.criteriaResults[0].met").value(true))
                .andExpect(jsonPath("$.preview.url").value("http://127.0.0.1:18081/"))
                .andExpect(jsonPath("$.technical").doesNotExist());
    }

    @Test
    void aSuperAdministratorAlsoReadsTheDiffEvidence() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.detail(eq(JOB), eq(AdminRole.SUPER_ADMIN))).thenReturn(detail(technical()));

        mockMvc.perform(get("/api/admin/coding/jobs/{jobId}", JOB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technical.baseSha").value(BASE_SHA))
                .andExpect(jsonPath("$.technical.changedPaths[0]").value(CHANGED_PATH));
    }

    @Test
    void anUnknownJobAnswersNotFoundRatherThanAnEmptyScreen() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        when(service.detail(eq(JOB), any(AdminRole.class))).thenReturn(null);

        mockMvc.perform(get("/api/admin/coding/jobs/{jobId}", JOB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListIsAvailableToAGeneralAdministrator() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        when(service.list(20)).thenReturn(new CodingConsoleContract.JobList("1.0", List.of(
                new CodingConsoleContract.JobSummary(
                        JOB, "backend", "회원 목록에 가입일도 보이게 해줘",
                        "WAITING_APPROVAL", "코드 검토", null,
                        Instant.parse("2026-09-02T00:00:00Z"), null))));

        mockMvc.perform(get("/api/admin/coding/jobs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].jobId").value(JOB.toString()))
                .andExpect(jsonPath("$.items[0].repository").value("backend"));
    }

    @Test
    void anAnonymousCallerReadsNothing() throws Exception {
        mockMvc.perform(get("/api/admin/coding/jobs/{jobId}", JOB))
                .andExpect(status().isUnauthorized());
    }

    private void authenticate(AdminRole role) {
        Jwt jwt = Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "HS256")
                .subject(ACTOR_ID.toString())
                .claim("token_type", "access")
                .build();
        when(accessJwtDecoder.decode(ACCESS_TOKEN)).thenReturn(jwt);
        when(authService.loadActor(ACTOR_ID)).thenReturn(
                new AuthenticatedActor(ACTOR_ID, "관리자", role));
    }

    private static CodingConsoleContract.JobDetail detail(
            CodingConsoleContract.Technical technical) {
        return new CodingConsoleContract.JobDetail(
                "1.0", JOB, "backend", "회원 목록에 가입일도 보이게 해줘",
                "WAITING_APPROVAL", "코드 검토", 1, 3,
                new CodingConsoleContract.Plan(
                        "회원 목록에 가입일 칸을 더합니다.", List.of("목록에 가입일이 보인다")),
                new CodingConsoleContract.Report(
                        "가입일을 추가했습니다.",
                        List.of(new CodingConsoleContract.CriterionResult(
                                "목록에 가입일이 보인다", true))),
                null, List.of(),
                new CodingConsoleContract.PreviewLink(true, "http://127.0.0.1:18081/"),
                technical, Instant.parse("2026-09-02T00:00:00Z"), null);
    }

    private static CodingConsoleContract.Technical technical() {
        return new CodingConsoleContract.Technical(
                BASE_SHA, BASE_SHA, "sha256:" + "c".repeat(64),
                List.of(CHANGED_PATH), "maven-verify", null, null);
    }
}
