package org.urizo.axmodulestudio.backend.coding.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingConsoleService;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobIntakeService;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;

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
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID APPROVAL = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String ACCESS_TOKEN = "coding-console-test-token";
    private static final String BASE_SHA = "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHANGED_PATH = "src/main/java/org/urizo/Member.java";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodingConsoleService service;

    @MockitoBean
    private CodingJobIntakeService intake;

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
                .andExpect(jsonPath("$.technical.changedPaths[0]").value(CHANGED_PATH))
                // The diff body is the evidence the merge approval is actually judged on.
                .andExpect(jsonPath("$.technical.diff").value(
                        org.hamcrest.Matchers.containsString("데모 확인")));
    }

    @Test
    void theApprovalEvidenceReachesTheScreenOrNoDecisionCanBeSubmitted() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        when(service.detail(eq(JOB), eq(AdminRole.GENERAL_ADMIN))).thenReturn(detail(null));

        mockMvc.perform(get("/api/admin/coding/jobs/{jobId}", JOB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingApproval.approvalId").value(APPROVAL.toString()))
                // The decision endpoint refuses any traceId but the Job's own, so the read
                // must hand it down or no approval can ever be submitted.
                .andExpect(jsonPath("$.pendingApproval.traceId").value(TRACE.toString()))
                .andExpect(jsonPath("$.pendingApproval.nodeId").value("scope_approval"))
                .andExpect(jsonPath("$.pendingApproval.stage").value("SCOPE"))
                .andExpect(jsonPath("$.pendingApproval.stageRound").value(1))
                .andExpect(jsonPath("$.pendingApproval.requiredRole").value("GENERAL_ADMIN"))
                .andExpect(jsonPath("$.pendingApproval.expectedStateVersion").value(4))
                .andExpect(jsonPath("$.pendingApproval.pipelineAttempt").value(1));
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
                        "WAITING_APPROVAL", "코드 검토",
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
                pendingApproval(), List.of(),
                new CodingConsoleContract.PreviewLink(true, "http://127.0.0.1:18081/"),
                technical, Instant.parse("2026-09-02T00:00:00Z"), null);
    }

    /**
     * Everything the decision endpoint will demand back. The screen cannot derive any of it -
     * approvalId is a hash of the stage and round - so the read has to carry it or no approval
     * can ever be submitted.
     */
    private static CodingConsoleContract.PendingApproval pendingApproval() {
        return new CodingConsoleContract.PendingApproval(
                APPROVAL, TRACE, "scope_approval", "SCOPE", 1, "GENERAL_ADMIN", 4, 1, null, null);
    }

    private static CodingConsoleContract.Technical technical() {
        return new CodingConsoleContract.Technical(
                BASE_SHA, BASE_SHA, "sha256:" + "c".repeat(64),
                List.of(CHANGED_PATH),
                "diff --git a/README.md b/README.md" + '
' + "+데모 확인",
                "maven-verify", null, null);
    }

    @Test
    void aRequestCarriesOnlyTheRepositoryAndTheKoreanTextTheAdministratorTyped() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        when(intake.create(any(), any(), any(), any())).thenReturn(created());

        mockMvc.perform(post("/api/admin/coding/jobs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"backend\","
                                + "\"requestText\":\"회원 목록에 가입일도 보이게 해줘\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/admin/coding/jobs/" + JOB));

        ArgumentCaptor<CodingConsoleContract.CreateJobRequest> sent =
                ArgumentCaptor.forClass(CodingConsoleContract.CreateJobRequest.class);
        verify(intake).create(any(), any(), any(), sent.capture());
        assertThat(sent.getValue().repository()).isEqualTo("backend");
        assertThat(sent.getValue().requestText()).isEqualTo("회원 목록에 가입일도 보이게 해줘");
    }

    @Test
    void aStalledRunnerBecomesAnAnswerRatherThanAHang() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        // The runner lives outside Docker and a person has to start it. Saying so is the whole
        // point of waiting for the sha rather than creating a Job that would never move.
        when(intake.create(any(), any(), any(), any())).thenThrow(
                new CodingJobLifecycleException(
                        "CODING_RUNNER_NOT_RESPONDING",
                        "실행기가 응답하지 않습니다. 실행기가 켜져 있는지 확인해 주세요.",
                        HttpStatus.SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/api/admin/coding/jobs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"backend\",\"requestText\":\"고쳐줘\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CODING_RUNNER_NOT_RESPONDING"));
    }

    private static CodingHandlerContract.CreateCodingJobResponse created() {
        CodingJobLifecycleContract.JobResponse job = new CodingJobLifecycleContract.JobResponse(
                "1.0", JOB, TRACE, ACTOR_ID, ACTOR_ID, ACTOR_ID, ACTOR_ID,
                "start", CodingJobLifecycleContract.Status.PENDING, 1,
                "coding-plan-v1", List.of("CHAT"), List.of("start"),
                Instant.parse("2026-09-02T01:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"), null,
                Instant.parse("2026-09-02T00:00:00Z"), null, null);
        return new CodingHandlerContract.CreateCodingJobResponse("1.0", job, null);
    }
}
