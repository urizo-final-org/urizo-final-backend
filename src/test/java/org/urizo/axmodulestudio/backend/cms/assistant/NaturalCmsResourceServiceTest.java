package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PostView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class NaturalCmsResourceServiceTest {

    /**
     * 울타리를 아직 저장한 적 없는 구성.
     *
     * <p>아래 대부분의 테스트는 울타리와 무관한 규칙을 본다. 저장 전에는 코드가 연 그대로
     * 동작해야 하므로, 이 구성에서 통과한다는 것이 곧 "설정을 만들어도 기존 동작이 그대로다"의
     * 회귀 검사가 된다. 울타리를 켠 경우는 아래 울타리 전용 테스트가 따로 본다.
     */
    private static NaturalCmsResourceService newResources(
            CmsService cms, CmsRequestValidator validator, ObjectMapper mapper) {
        return new NaturalCmsResourceService(cms, validator, mapper, guardrails(
                NaturalCmsGuardrail.unconfigured()));
    }

    private static NaturalCmsGuardrailStore guardrails(NaturalCmsGuardrail guardrail) {
        NaturalCmsGuardrailStore store = mock(NaturalCmsGuardrailStore.class);
        when(store.current()).thenReturn(guardrail);
        return store;
    }

    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");
    /** 마크다운 3문법은 이제 게시물에만 남았다. 그 규칙을 확인하는 대상이다. */
    private static final NaturalCmsContract.ResourceRef POST_RESOURCE =
            new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12");

    /** 컨텐츠 본문은 편집기가 만든 문서다. 문단 하나짜리 최소 문서를 만든다. */
    private static String document(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}";
    }
    /** 반영 경로가 Job의 요청자를 그대로 받는다. 게시물 등록의 작성자로만 쓰인다. */
    private static final UUID AUTHOR =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static ContentView content(String title, String body) {
        return new ContentView(
                7,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "Admin",
                title,
                body,
                Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:01:00Z"));
    }

    @Test
    void validatesWithoutMutationAndAppliesThroughTheExistingCmsService() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        CmsRequestValidator validator = mock(CmsRequestValidator.class);
        NaturalCmsResourceService resources =
                newResources(cms, validator, mapper);
        when(cms.content(7)).thenReturn(content("Old title", document("Old body")));
        ObjectNode command = mapper.createObjectNode().put("operation", "UPDATE");
        command.putObject("fields")
                .put("title", "New title")
                .put("body", document("New body"));

        JsonNode validated = resources.validateCommand(RESOURCE, command);

        assertThat(validated).isEqualTo(command);
        ArgumentCaptor<CmsRequests.ArticleRequest> request =
                ArgumentCaptor.forClass(CmsRequests.ArticleRequest.class);
        verify(validator).validate(request.capture());
        assertThat(request.getValue().title()).isEqualTo("New title");
        assertThat(request.getValue().body()).isEqualTo(document("New body"));
        verify(cms, never()).updateContent(7, "New title", document("New body"));

        when(cms.updateContent(7, "New title", document("New body")))
                .thenReturn(content("New title", document("New body")));

        JsonNode result = resources.apply(RESOURCE, command, AUTHOR);

        assertThat(result.path("id").asLong()).isEqualTo(7);
        assertThat(result.path("title").asText()).isEqualTo("New title");
        verify(cms).updateContent(7, "New title", document("New body"));
    }

    @Test
    void keepsTheCurrentValueForFieldsTheCommandDoesNotSend() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", document("Old body")));
        when(cms.updateContent(7, "New title", document("Old body")))
                .thenReturn(content("New title", document("Old body")));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title"}}
                """);

        resources.apply(RESOURCE, command, AUTHOR);

        verify(cms).updateContent(7, "New title", document("Old body"));
    }

    @Test
    void rejectsFieldNamesOutsideTheContentSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title","author":"Someone"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("body, title");
    }

    @Test
    void rejectsAnEmptyFieldSet() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class);
    }

    /**
     * 마크다운 3문법 제한은 게시물에만 남았다.
     *
     * <p>컨텐츠는 `AI05-016`으로 편집기 문서를 쓰게 되어 이 규칙을 타지 않는다.
     */
    @Test
    void acceptsTheThreeSupportedMarkdownFormsInAPostBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.post(12)).thenReturn(post(12, 4, "공지", "본문"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"body":"## 제목\\n\\n**강조** 문구입니다.\\n\\n- 항목 하나\\n- 항목 둘"}}
                """);

        assertThatCode(() -> resources.validateCommand(POST_RESOURCE, command))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "# 제목", "### 제목", "> 인용", "* 목록", "+ 목록", "1. 순서 목록",
        "```java", "| 표 |", "---", "![그림](/a.png)", "[링크](/a)",
    })
    void rejectsMarkdownTheEditorCannotRenderInAPostBody(String line) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.post(12)).thenReturn(post(12, 4, "공지", "본문"));
        JsonNode command = mapper.valueToTree(java.util.Map.of(
                "operation", "UPDATE", "fields", java.util.Map.of("body", "본문\n" + line)));

        assertThatThrownBy(() -> resources.validateCommand(POST_RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("headings (##)");
    }

    @Test
    void updatesAMenuWithNumberAndNullFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.menu(3)).thenReturn(new MenuView(3, "회사소개", "/about", 5L, 1, "NONE", null));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"name":"회사 소개","displayOrder":2,"parentId":null}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "3"), command, AUTHOR);

        verify(cms).updateMenu(3, "회사 소개", "/about", null, 2, "NONE", null);
    }

    @Test
    void updatesABoardAndClearsAnOptionalTextField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.board(4)).thenReturn(new BoardView(
                4, "공지사항", "안내 게시판",
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z")));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"description":null}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("BOARD", "4"), command, AUTHOR);

        verify(cms).updateBoard(4, "공지사항", null);
    }

    @Test
    void updatesATemplateAddressedByItsKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.templates()).thenReturn(java.util.List.of(template()));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"siteName":"새 사이트"}}
                """);

        NaturalCmsContract.ResourceRef resource =
                new NaturalCmsContract.ResourceRef("TEMPLATE", "classic");
        assertThat(resources.snapshot(resource).path("id").asText()).isEqualTo("classic");
        resources.apply(resource, command, AUTHOR);

        verify(cms).saveTemplate(
                "classic", "wide", "#112233", "새 사이트", "머리말", "꼬리말",
                "/hero.png", "환영합니다", "부제", "자세히", "/about");
    }

    @Test
    void rejectsAFieldValueOfTheWrongJsonType() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"displayOrder":"2"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("MENU", "3"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("displayOrder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MENU", "BOARD", "CONTENT", "TEMPLATE"})
    void acceptsTheFourScreenResources(String type) {
        assertThatCode(() -> new NaturalCmsContract.ResourceRef(type, "1"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "MEMBER", "content", "CMS_COMPOSITE"})
    void rejectsResourcesOutsideTheScreenBoundary(String type) {
        assertThatThrownBy(() -> new NaturalCmsContract.ResourceRef(type, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsAMenuAtTheEndOfItsSiblingsWhenTheCommandGivesNoPosition() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"name":"자료실","path":"/support/archive",
                 "parentId":40}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "new"), command, AUTHOR);

        verify(cms).createMenu("자료실", "/support/archive", 40L, 42, "NONE", null);
        verify(cms, never()).updateMenu(
                anyLong(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void createsATopMenuAtTheGivenPositionAndRenumbersTheGroupWithItsChildren() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        when(cms.createMenu("회사", "/company", null, 10, "NONE", null))
                .thenReturn(new MenuView(50, "회사", "/company", null, 10, "NONE", null));
        when(cms.menu(50)).thenReturn(new MenuView(50, "회사", "/company", null, 10, "NONE", null));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"name":"회사","path":"/company","position":1}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "new"), command, AUTHOR);

        verify(cms).createMenu("회사", "/company", null, 10, "NONE", null);
        verify(cms).updateMenu(10, "소개", "/about", null, 20, "NONE", null);
        verify(cms).updateMenu(11, "회사 소개", "/about/company", 10L, 21, "CONTENT", 3L);
        verify(cms).updateMenu(12, "비전", "/about/vision", 10L, 22, "CONTENT", 4L);
        verify(cms).updateMenu(40, "고객지원", "/support", null, 30, "NONE", null);
        verify(cms).updateMenu(41, "문의하기", "/support/contact", 40L, 31, "CONTENT", 5L);
    }

    @Test
    void movesAMenuByItsOrdinalPositionAndRenumbersOnlyItsOwnGroup() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"position":1}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "12"), command, AUTHOR);

        verify(cms).updateMenu(12, "비전", "/about/vision", 10L, 11, "CONTENT", 4L);
        verify(cms).updateMenu(11, "회사 소개", "/about/company", 10L, 12, "CONTENT", 3L);
        verify(cms, never()).updateMenu(
                eq(41L), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void clampsAPositionBeyondTheSiblingCountToTheLastPlace() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"position":9}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "11"), command, AUTHOR);

        verify(cms).updateMenu(11, "회사 소개", "/about/company", 10L, 12, "CONTENT", 3L);
        verify(cms).updateMenu(12, "비전", "/about/vision", 10L, 11, "CONTENT", 4L);
    }

    @Test
    void deletesAMenuThroughTheExistingCascadeAndReportsTheRemovedState() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        JsonNode removed = resources.apply(
                new NaturalCmsContract.ResourceRef("MENU", "10"), command, AUTHOR);

        assertThat(removed.path("name").asText()).isEqualTo("소개");
        verify(cms).deleteMenu(10);
    }

    @Test
    void rejectsADeleteCommandThatCarriesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{"name":"소개"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("MENU", "10"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("carries no fields");
    }

    @Test
    void refusesADeleteThatWouldRemoveMoreMenusThanOneCommandMay() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        java.util.List<MenuView> menus = new java.util.ArrayList<>();
        menus.add(new MenuView(10, "소개", "/about", null, 10, "NONE", null));
        for (int index = 1; index <= 10; index++) {
            menus.add(new MenuView(
                    10 + index, "하위 " + index, "/about/" + index, 10L, 10 + index,
                    "NONE", null));
        }
        when(cms.menus()).thenReturn(java.util.List.copyOf(menus));
        when(cms.menu(10)).thenReturn(menus.get(0));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("MENU", "10"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("removes 11 menus");
        verify(cms, never()).deleteMenu(anyLong());
    }

    /** 템플릿만 남은 수정 전용 리소스다. 컨텐츠는 `AI05-015`로 등록·삭제가 열렸다. */
    @Test
    void keepsCreateAndDeleteClosedForResourcesThatOnlyOpenUpdate() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"siteName":"새 사이트"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("TEMPLATE", "DEFAULT"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("operations only: UPDATE");
    }

    @Test
    void snapshotsANewContentWithoutReadingTheDatabase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode state = resources.snapshot(
                new NaturalCmsContract.ResourceRef("CONTENT", "new"));

        // 명령 단계가 이 필드 이름으로 쓸 수 있는 필드를 정하므로 빈 자리를 갖춘 틀을 준다.
        assertThat(state.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "title", "body");
        assertThat(state.path("id").asText()).isEqualTo("new");
        assertThat(state.path("title").isNull()).isTrue();
        verify(cms, never()).content(anyLong());
    }

    @Test
    void createsAContentAndKeepsTheRequesterAsAuthor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.createContent(AUTHOR, "채용 안내", document("모집합니다")))
                .thenReturn(content("채용 안내", document("모집합니다")));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        ObjectNode command = mapper.createObjectNode().put("operation", "CREATE");
        command.putObject("fields")
                .put("title", "채용 안내")
                .put("body", document("모집합니다"));

        JsonNode created = resources.apply(
                new NaturalCmsContract.ResourceRef("CONTENT", "new"), command, AUTHOR);

        assertThat(created.path("id").asLong()).isEqualTo(7);
        // 등록은 현재 값이 없다. 기존 행을 읽지 않는다.
        verify(cms).createContent(AUTHOR, "채용 안내", document("모집합니다"));
        verify(cms, never()).content(anyLong());
    }

    /**
     * 등록 경로도 수정과 같은 문서 검사를 탄다. 검증과 반영이 같은 merged를 쓰기 때문이다.
     *
     * <p>모델이 구조를 틀리게 만들면 여기서 거부되고 Job은 반려로 정상 종료된다.
     */
    @Test
    void refusesAContentBodyThatIsNotAnEditorDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        ObjectNode command = mapper.createObjectNode().put("operation", "CREATE");
        command.putObject("fields").put("title", "안내").put("body", "## 모집\n\n- 개발자");

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("CONTENT", "new"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("편집기가 만든 문서");
        verify(cms, never()).createContent(any(), any(), any());
    }

    @Test
    void refusesAContentDocumentThatUsesANodeTheRendererCannotDraw() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        ObjectNode command = mapper.createObjectNode().put("operation", "CREATE");
        command.putObject("fields").put("title", "안내").put("body",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"table\",\"content\":[]}]}");

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("CONTENT", "new"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("쓸 수 없는");
        verify(cms, never()).createContent(any(), any(), any());
    }

    /** 이미지는 우리가 저장한 것만 가리킨다. 외부 주소는 저장 전에 막는다. */
    @Test
    void refusesAContentImageThatPointsOutsideThisCms() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        ObjectNode command = mapper.createObjectNode().put("operation", "CREATE");
        command.putObject("fields").put("title", "안내").put("body",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":"
                        + "{\"src\":\"https://example.test/a.png\"}}]}");

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("CONTENT", "new"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("올린 것만");
        verify(cms, never()).createContent(any(), any(), any());
    }

    /**
     * 컨텐츠 삭제는 막지 않는다. 연결한 메뉴가 있어도 기존 {@code deleteContent}가 연결만 끊는다.
     *
     * <p>무엇이 끊기는지는 화면이 승인 전에 보여준다. 게시판 삭제와 다른 점이다.
     */
    @Test
    void deletesAContentAndReportsTheRemovedState() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.content(7)).thenReturn(content("회사 소개", "## 소개"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        JsonNode removed = resources.apply(RESOURCE, command, AUTHOR);

        assertThat(removed.path("title").asText()).isEqualTo("회사 소개");
        verify(cms).deleteContent(7);
    }

    @Test
    void refusesAContentDeleteThatCarriesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{"title":"회사 소개"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("DELETE command carries no fields");
        verify(cms, never()).deleteContent(anyLong());
    }

    @Test
    void snapshotsANewMenuWithoutReadingTheDatabase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode state = resources.snapshot(new NaturalCmsContract.ResourceRef("MENU", "new"));

        // 명령 단계가 이 필드 이름으로 쓸 수 있는 필드를 정하므로 빈 자리를 갖춘 틀을 준다.
        assertThat(state.path("id").asText()).isEqualTo("new");
        assertThat(state.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "path", "parentId", "position", "targetType", "targetId");
        assertThat(state.path("targetType").asText()).isEqualTo("NONE");
        assertThat(state.path("name").isNull()).isTrue();
        assertThat(state.has("displayOrder")).isFalse();
        verify(cms, never()).menu(anyLong());
    }

    @Test
    void givesTheModelOrdinalsAndLinkTargetsButNeverTheMenuNumbers() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        when(cms.contents()).thenReturn(java.util.List.of(content("회사 소개", "본문")));
        when(cms.boards()).thenReturn(java.util.List.of(new BoardView(
                1, "공지사항", "안내",
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z"))));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode context = resources.promptContext(
                new NaturalCmsContract.ResourceRef("MENU", "12"));

        assertThat(context.path("menus")).hasSize(5);
        assertThat(context.path("menus").get(0).path("position").asInt()).isEqualTo(1);
        assertThat(context.path("menus").get(2).path("name").asText()).isEqualTo("비전");
        assertThat(context.path("menus").get(2).path("position").asInt()).isEqualTo(2);
        assertThat(context.path("menus").get(0).has("displayOrder")).isFalse();
        assertThat(context.path("contents").get(0).path("id").asLong()).isEqualTo(7);
        assertThat(context.path("boards").get(0).path("name").asText()).isEqualTo("공지사항");
    }

    @Test
    void snapshotsANewBoardWithoutReadingTheDatabase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode state = resources.snapshot(new NaturalCmsContract.ResourceRef("BOARD", "new"));

        // 명령 단계가 이 필드 이름으로 쓸 수 있는 필드를 정하므로 빈 자리를 갖춘 틀을 준다.
        assertThat(state.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "description");
        assertThat(state.path("id").asText()).isEqualTo("new");
        assertThat(state.path("name").isNull()).isTrue();
        verify(cms, never()).board(anyLong());
    }

    @Test
    void createsABoardAndLeavesTheDescriptionOutWhenTheRequestGivesNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.createBoard("자료실", null)).thenReturn(board(9, "자료실", null));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"name":"자료실"}}
                """);

        JsonNode created = resources.apply(
                new NaturalCmsContract.ResourceRef("BOARD", "new"), command, AUTHOR);

        assertThat(created.path("id").asLong()).isEqualTo(9);
        verify(cms).createBoard("자료실", null);
    }

    @Test
    void deletesAnEmptyBoardAndReportsTheRemovedState() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.board(4)).thenReturn(board(4, "자료실", "빈 게시판"));
        when(cms.posts(4)).thenReturn(java.util.List.of());
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        JsonNode removed = resources.apply(
                new NaturalCmsContract.ResourceRef("BOARD", "4"), command, AUTHOR);

        assertThat(removed.path("name").asText()).isEqualTo("자료실");
        verify(cms).deleteBoard(4);
    }

    /**
     * 기준은 0건이다. 게시물이 남아 있으면 자연어로 지우지 않는다.
     *
     * <p>기존 CMS의 {@code deleteBoard}는 게시물을 먼저 지우고 진행한다. 그 경로는 그대로 두고
     * 자연어에만 선을 둔다.
     */
    @Test
    void refusesToDeleteABoardThatStillHasPosts() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.posts(4)).thenReturn(java.util.List.of(
                post(12, 4, "공지", "본문"), post(13, 4, "안내", "본문")));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("BOARD", "4"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("still has 2 posts");
        verify(cms, never()).deleteBoard(anyLong());
    }

    /**
     * 판정 단계가 삭제 조건을 스스로 확인할 수 있게 게시물 수를 준다.
     *
     * <p>제목까지 주지 않는다. 필요한 것은 0인지 아닌지뿐이다.
     */
    @Test
    void givesTheModelThePostCountSoItCanJudgeBoardDeletion() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.posts(4)).thenReturn(java.util.List.of(
                post(12, 4, "공지", "본문"), post(13, 4, "안내", "본문")));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode context = resources.promptContext(
                new NaturalCmsContract.ResourceRef("BOARD", "4"));

        assertThat(context.path("posts").asInt()).isEqualTo(2);
        assertThat(context.fieldNames()).toIterable().containsExactly("posts");
    }

    @Test
    void leavesTheReferenceEmptyForABoardThatDoesNotExistYet() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        assertThat(resources.promptContext(
                new NaturalCmsContract.ResourceRef("BOARD", "new"))).isNull();
        verify(cms, never()).posts(anyLong());
    }

    @Test
    void snapshotsANewPostWithTheCombinedTargetIdSoTheToolCanMatchIt() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);

        JsonNode state = resources.snapshot(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:new"));

        assertThat(state.path("id").asText()).isEqualTo("board:4:post:new");
        assertThat(state.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "title", "body");
        verify(cms, never()).post(anyLong());
    }

    @Test
    void createsAPostInTheBoardTheTargetIdCarriesAndKeepsTheRequesterAsAuthor()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.board(4)).thenReturn(board(4, "공지사항", "안내"));
        when(cms.createPost(AUTHOR, 4, "점검 안내", "## 안내\n\n- 항목"))
                .thenReturn(post(21, 4, "점검 안내", "## 안내\n\n- 항목"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"title":"점검 안내","body":"## 안내\\n\\n- 항목"}}
                """);

        JsonNode created = resources.apply(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:new"),
                command, AUTHOR);

        assertThat(created.path("id").asLong()).isEqualTo(21);
        verify(cms).board(4);
        verify(cms).createPost(AUTHOR, 4, "점검 안내", "## 안내\n\n- 항목");
    }

    @Test
    void updatesAPostAndKeepsTheFieldsTheCommandDoesNotSend() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.post(12)).thenReturn(post(12, 4, "옛 제목", "옛 본문"));
        when(cms.updatePost(12, "새 제목", "옛 본문"))
                .thenReturn(post(12, 4, "새 제목", "옛 본문"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"새 제목"}}
                """);

        resources.apply(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12"),
                command, AUTHOR);

        verify(cms).updatePost(12, "새 제목", "옛 본문");
    }

    /** 4-4. 대상 게시물이 화면에서 연 게시판의 것이 아니면 건드리지 않는다. */
    @Test
    void refusesAPostThatBelongsToAnotherBoard() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.post(12)).thenReturn(post(12, 7, "다른 게시판 글", "본문"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"새 제목"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("belongs to another board");
        verify(cms, never()).updatePost(anyLong(), any(), any());
    }

    @Test
    void deletesAPostAfterConfirmingItsBoard() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.post(12)).thenReturn(post(12, 4, "지울 글", "본문"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        JsonNode removed = resources.apply(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12"),
                command, AUTHOR);

        assertThat(removed.path("title").asText()).isEqualTo("지울 글");
        verify(cms).deletePost(12);
    }

    /** 게시판 사이 이동은 열지 않는다. 허용 필드 밖이라 그 자리에서 거부된다. */
    @Test
    void keepsAPostInItsBoardByRejectingABoardField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"boardId":7}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("fields only: body, title");
    }

    /** 게시물도 컨텐츠와 같은 렌더러를 타므로 같은 문법 제한을 받는다. */
    @Test
    void rejectsPostBodyMarkdownTheEditorCannotRender() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.post(12)).thenReturn(post(12, 4, "제목", "본문"));
        NaturalCmsResourceService resources =
                newResources(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.valueToTree(java.util.Map.of(
                "operation", "UPDATE",
                "fields", java.util.Map.of("body", "본문\n| 표 |")));

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("headings (##)");
    }

    @Test
    void rejectsAPostTargetIdThatDoesNotCarryItsBoard() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = newResources(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);

        // 게시판 id 자리가 비면 게시판 대상으로 읽혀 숫자 id 검사에서 멈춘다.
        assertThatThrownBy(() -> resources.snapshot(
                new NaturalCmsContract.ResourceRef("BOARD", "post:12")))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("BOARD id is invalid");
    }

    private static BoardView board(long id, String name, String description) {
        return new BoardView(
                id, name, description,
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z"));
    }

    private static PostView post(long id, long boardId, String title, String body) {
        return new PostView(
                id, boardId, AUTHOR, "Admin", title, body,
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z"));
    }

    /** 대메뉴 둘과 하위 셋. 번호는 시드 관례대로 대메뉴 10 간격, 하위는 부모 구역 안이다. */
    private static CmsService menuTree() {
        CmsService cms = mock(CmsService.class);
        MenuView about = new MenuView(10, "소개", "/about", null, 10, "NONE", null);
        MenuView company =
                new MenuView(11, "회사 소개", "/about/company", 10L, 11, "CONTENT", 3L);
        MenuView vision = new MenuView(12, "비전", "/about/vision", 10L, 12, "CONTENT", 4L);
        MenuView support = new MenuView(40, "고객지원", "/support", null, 40, "NONE", null);
        MenuView contact =
                new MenuView(41, "문의하기", "/support/contact", 40L, 41, "CONTENT", 5L);
        when(cms.menus()).thenReturn(
                java.util.List.of(about, company, vision, support, contact));
        when(cms.menu(10)).thenReturn(about);
        when(cms.menu(11)).thenReturn(company);
        when(cms.menu(12)).thenReturn(vision);
        when(cms.menu(40)).thenReturn(support);
        when(cms.menu(41)).thenReturn(contact);
        when(cms.updateMenu(
                anyLong(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> new MenuView(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5),
                        call.getArgument(6)));
        when(cms.createMenu(any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> new MenuView(
                        99, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        return cms;
    }

    private static TemplateView template() {
        return new TemplateView(
                "classic", "wide", "#112233", "기존 사이트", "머리말", "꼬리말",
                "/hero.png", "환영합니다", "부제", "자세히", "/about",
                true, Instant.parse("2026-08-30T00:01:00Z"));
    }

    // ── 울타리 ───────────────────────────────────────────────────────────────
    //
    // 가드레일은 Handler가 연 것과 교집합으로만 동작한다. 아래 테스트는 그 교집합이
    // 좁히기만 하고 넓히지 못하는지, 그리고 저장 전에는 아무 영향이 없는지를 고정한다.

    /** {@code menuTree()}가 실제로 갖고 있는 대메뉴. 병합 단계가 현재 값을 읽는다. */
    private static final NaturalCmsContract.ResourceRef MENU_RESOURCE =
            new NaturalCmsContract.ResourceRef("MENU", "10");

    private static NaturalCmsResourceService fenced(
            CmsService cms, ObjectMapper mapper, NaturalCmsGuardrail guardrail) {
        return new NaturalCmsResourceService(
                cms, mock(CmsRequestValidator.class), mapper, guardrails(guardrail));
    }

    /** 대상 하나의 동작만 담은 가드레일. 나머지 대상은 목록에 없어 코드 기본값을 따른다. */
    private static NaturalCmsGuardrail saved(String resourceKey, String... operations) {
        return new NaturalCmsGuardrail(true, Map.of(resourceKey, Set.of(operations)));
    }

    /** 저장 전에는 코드가 연 그대로다. 설치 직후 자연어 CMS가 멎으면 안 된다. */
    @Test
    void keepsEveryOpenedOperationBeforeTheGuardrailIsSaved() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(
                menuTree(), mapper, NaturalCmsGuardrail.unconfigured());

        assertThatCode(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"DELETE\",\"fields\":{}}")))
                .doesNotThrowAnyException();
    }

    /** 닫은 동작을 실은 명령은 거절되고, 화면이 가려 말할 수 있게 전용 코드가 붙는다. */
    @Test
    void refusesAnOperationTheGuardrailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(menuTree(), mapper,
                saved(NaturalCmsGuardrail.MENU, "CREATE", "UPDATE"));

        assertThatThrownBy(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"DELETE\",\"fields\":{}}")))
                .isInstanceOf(NaturalCmsException.class)
                .extracting(failure -> ((NaturalCmsException) failure).code())
                .isEqualTo(NaturalCmsRefusal.OPERATION_NOT_ALLOWED.code());
    }

    /** 열어 둔 동작은 그대로 통과한다. 닫는 것은 그 대상의 그 동작뿐이다. */
    @Test
    void acceptsAnOperationTheGuardrailLeftOpen() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(menuTree(), mapper,
                saved(NaturalCmsGuardrail.MENU, "CREATE", "UPDATE"));

        assertThatCode(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"UPDATE\",\"fields\":{\"name\":\"새 이름\"}}")))
                .doesNotThrowAnyException();
    }

    /** 설정은 좁히기만 한다. 코드가 열지 않은 동작은 허용 목록에 넣어도 통과하지 못한다. */
    @Test
    void cannotOpenAnOperationTheHandlerDoesNotDeclare() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.templates()).thenReturn(List.of(template()));
        // 템플릿 Handler는 UPDATE만 연다. 설정에 CREATE를 넣어도 열리지 않아야 한다.
        NaturalCmsResourceService resources = fenced(cms, mapper,
                saved("TEMPLATE", "CREATE", "UPDATE"));

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("TEMPLATE", "classic"),
                mapper.readTree("{\"operation\":\"CREATE\",\"fields\":{\"siteName\":\"새 이름\"}}")))
                .isInstanceOf(NaturalCmsException.class)
                .extracting(failure -> ((NaturalCmsException) failure).code())
                .isEqualTo("CMS_COMMAND_INVALID");
    }

    /** 한 대상의 삭제를 닫아도 만들고 고치는 것은 남는다. */
    @Test
    void refusesDeleteWhileKeepingTheOtherOperations() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(menuTree(), mapper,
                saved(NaturalCmsGuardrail.MENU, "CREATE", "UPDATE"));

        assertThatThrownBy(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"DELETE\",\"fields\":{}}")))
                .isInstanceOf(NaturalCmsException.class)
                .extracting(failure -> ((NaturalCmsException) failure).code())
                .isEqualTo(NaturalCmsRefusal.OPERATION_NOT_ALLOWED.code());

        assertThatCode(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"UPDATE\",\"fields\":{\"name\":\"새 이름\"}}")))
                .doesNotThrowAnyException();
    }

    /**
     * Snapshot은 가드레일과 무관하다.
     *
     * <p>관리자가 정하는 단위가 대상별 동작이라 필드를 뺄 이유가 없다. 모델이 현재 값을
     * 못 보면 바꾸지 않은 필드를 채울 수 없어 수정 자체가 어그러진다.
     */
    @Test
    void keepsEveryFieldInTheSnapshot() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(menuTree(), mapper,
                saved(NaturalCmsGuardrail.MENU, "UPDATE"));

        ObjectNode state = resources.snapshot(new NaturalCmsContract.ResourceRef("MENU", "10"));

        assertThat(state.has("id")).isTrue();
        assertThat(state.has("name")).isTrue();
        assertThat(state.has("path")).isTrue();
        assertThat(state.has("parentId")).isTrue();
    }

    /**
     * 가드레일이 관리하지 않는 대상은 저장 뒤에도 코드가 연 그대로다.
     *
     * <p>TEMPLATE은 선택 표의 CHECK에서도 빠져 있다. 관리 대상이 아닌 것을 "선택된 적 없음"으로
     * 읽으면 저장 한 번에 그 대상이 통째로 닫힌다.
     */
    @Test
    void leavesUnmanagedResourcesOpenAfterTheGuardrailIsSaved() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        when(cms.templates()).thenReturn(List.of(template()));
        NaturalCmsResourceService resources = fenced(cms, mapper,
                saved(NaturalCmsGuardrail.MENU, "CREATE"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"siteName":"새 이름"}}
                """);

        assertThatCode(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("TEMPLATE", "classic"), command))
                .doesNotThrowAnyException();
    }

    /**
     * 닫는 것은 대상 하나다. 컨텐츠 삭제를 닫아도 메뉴 삭제는 그대로다.
     *
     * <p>전역 스위치 하나였을 때는 한 대상을 잠그려다 넷이 함께 잠겼다. 이 테스트가 그 회귀를 막는다.
     *
     * <p>저장은 언제나 열두 칸을 통째로 보내므로 관리 대상은 모두 목록에 오른다. 목록에 없는
     * 관리 대상은 "관리자가 전부 껐다"는 뜻이지 "기본값을 따른다"가 아니다.
     */
    @Test
    void closesOneResourceWithoutTouchingAnother() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(menuTree(), mapper,
                new NaturalCmsGuardrail(true, Map.of(
                        NaturalCmsGuardrail.MENU, Set.of("CREATE", "UPDATE", "DELETE"),
                        NaturalCmsGuardrail.CONTENT, Set.of("CREATE", "UPDATE"))));

        assertThatCode(() -> resources.validateCommand(MENU_RESOURCE,
                mapper.readTree("{\"operation\":\"DELETE\",\"fields\":{}}")))
                .doesNotThrowAnyException();
    }

    /** 화면이 그릴 목록은 Handler가 여는 것에서 나온다. 저장된 선택이 기준이 아니다. */
    @Test
    void reportsWhatTheHandlersCurrentlyOpen() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(
                mock(CmsService.class), mapper, NaturalCmsGuardrail.unconfigured());

        List<NaturalCmsResourceService.OpenResource> open = resources.openResources();

        assertThat(open).extracting(NaturalCmsResourceService.OpenResource::resourceKey)
                .containsExactly("MENU", "BOARD", "BOARD_POST", "CONTENT");
        assertThat(open.get(0).fields())
                .contains("name", "path", "parentId", "displayOrder", "position",
                        "targetType", "targetId");
        assertThat(open.get(3).fields()).containsExactlyInAnyOrder("title", "body");
    }

    /**
     * 닿을 수 없는 나머지 대상은 자기를 뺀 전부다.
     *
     * <p>근거는 판정 지시문이 아니라 Handler 고정이다. 대상이 정해지면 그 Handler 하나만
     * 쓰이므로 다른 대상의 표에 닿을 코드 경로가 없다. 그래서 설정 화면이 「할 수 없다」고
     * 말해도 과장이 아니다.
     */
    @Test
    void reportsTheOtherResourcesEachTargetCannotReach() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(
                mock(CmsService.class), mapper, NaturalCmsGuardrail.unconfigured());

        List<NaturalCmsResourceService.OpenResource> open = resources.openResources();

        assertThat(open.get(0).excludes())
                .containsExactlyInAnyOrder("BOARD", "BOARD_POST", "CONTENT", "TEMPLATE");
        // 게시판과 게시물은 서로를 뺀다. 한 화면이지만 Handler 가 다르다.
        assertThat(open.get(1).excludes())
                .containsExactlyInAnyOrder("MENU", "BOARD_POST", "CONTENT", "TEMPLATE");
        assertThat(open.get(2).excludes())
                .containsExactlyInAnyOrder("MENU", "BOARD", "CONTENT", "TEMPLATE");
        // 자기 자신은 넣지 않는다.
        assertThat(open.get(3).excludes()).doesNotContain("CONTENT");
    }

    /**
     * 가드레일이 어디에 있는지와 그 대상에만 걸리는 제약.
     *
     * <p>Handler 이름은 클래스에서 읽는다. 목록을 따로 적어 두면 Handler 를 바꿀 때 화면이
     * 옛 이름을 계속 보여준다. 숫자가 붙는 제약은 상한을 함께 실어 화면이 그 값을 적지 않게 한다.
     */
    @Test
    void reportsWhereEachGuardrailLivesAndWhatOnlyItLocks() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = fenced(
                mock(CmsService.class), mapper, NaturalCmsGuardrail.unconfigured());

        List<NaturalCmsResourceService.OpenResource> open = resources.openResources();

        assertThat(open.get(0).handlerName()).isEqualTo("MenuHandler");
        assertThat(open.get(0).dataTable()).isEqualTo("app.cms_menu");
        assertThat(open.get(0).locks())
                .extracting(NaturalCmsResourceService.Lock::key)
                .contains("MENU_DELETE_CASCADE");
        assertThat(open.get(0).locks())
                .filteredOn(lock -> "MENU_DELETE_CASCADE".equals(lock.key()))
                .extracting(NaturalCmsResourceService.Lock::value)
                .containsExactly(10);
        // 게시판과 게시물은 한 화면이지만 Handler 도 표도 다르다.
        assertThat(open.get(1).handlerName()).isEqualTo("BoardHandler");
        assertThat(open.get(2).handlerName()).isEqualTo("PostHandler");
        assertThat(open.get(2).dataTable()).isEqualTo("app.cms_post");
    }
}
