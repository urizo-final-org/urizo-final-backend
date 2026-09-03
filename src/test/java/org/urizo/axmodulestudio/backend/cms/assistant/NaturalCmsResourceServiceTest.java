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
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class NaturalCmsResourceServiceTest {

    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");

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
                new NaturalCmsResourceService(cms, validator, mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title","body":"New body"}}
                """);

        JsonNode validated = resources.validateCommand(RESOURCE, command);

        assertThat(validated).isEqualTo(command);
        ArgumentCaptor<CmsRequests.ArticleRequest> request =
                ArgumentCaptor.forClass(CmsRequests.ArticleRequest.class);
        verify(validator).validate(request.capture());
        assertThat(request.getValue().title()).isEqualTo("New title");
        assertThat(request.getValue().body()).isEqualTo("New body");
        verify(cms, never()).updateContent(7, "New title", "New body");

        when(cms.updateContent(7, "New title", "New body"))
                .thenReturn(content("New title", "New body"));

        JsonNode result = resources.apply(RESOURCE, command);

        assertThat(result.path("id").asLong()).isEqualTo(7);
        assertThat(result.path("title").asText()).isEqualTo("New title");
        verify(cms).updateContent(7, "New title", "New body");
    }

    @Test
    void keepsTheCurrentValueForFieldsTheCommandDoesNotSend() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        when(cms.updateContent(7, "New title", "Old body"))
                .thenReturn(content("New title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title"}}
                """);

        resources.apply(RESOURCE, command);

        verify(cms).updateContent(7, "New title", "Old body");
    }

    @Test
    void rejectsFieldNamesOutsideTheContentSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
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
        NaturalCmsResourceService resources = new NaturalCmsResourceService(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class);
    }

    @Test
    void acceptsTheThreeSupportedMarkdownForms() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"body":"## 제목\\n\\n**강조** 문구입니다.\\n\\n- 항목 하나\\n- 항목 둘"}}
                """);

        assertThatCode(() -> resources.validateCommand(RESOURCE, command)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "# 제목", "### 제목", "> 인용", "* 목록", "+ 목록", "1. 순서 목록",
        "```java", "| 표 |", "---", "![그림](/a.png)", "[링크](/a)",
    })
    void rejectsMarkdownTheEditorCannotRender(String line) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.valueToTree(java.util.Map.of(
                "operation", "UPDATE", "fields", java.util.Map.of("body", "본문\n" + line)));

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("headings (##)");
    }

    @Test
    void updatesAMenuWithNumberAndNullFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.menu(3)).thenReturn(new MenuView(3, "회사소개", "/about", 5L, 1, "NONE", null));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"name":"회사 소개","displayOrder":2,"parentId":null}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "3"), command);

        verify(cms).updateMenu(3, "회사 소개", "/about", null, 2, "NONE", null);
    }

    @Test
    void updatesABoardAndClearsAnOptionalTextField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.board(4)).thenReturn(new BoardView(
                4, "공지사항", "안내 게시판",
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z")));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"description":null}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("BOARD", "4"), command);

        verify(cms).updateBoard(4, "공지사항", null);
    }

    @Test
    void updatesATemplateAddressedByItsKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.templates()).thenReturn(java.util.List.of(template()));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"siteName":"새 사이트"}}
                """);

        NaturalCmsContract.ResourceRef resource =
                new NaturalCmsContract.ResourceRef("TEMPLATE", "classic");
        assertThat(resources.snapshot(resource).path("id").asText()).isEqualTo("classic");
        resources.apply(resource, command);

        verify(cms).saveTemplate(
                "classic", "wide", "#112233", "새 사이트", "머리말", "꼬리말",
                "/hero.png", "환영합니다", "부제", "자세히", "/about");
    }

    @Test
    void rejectsAFieldValueOfTheWrongJsonType() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = new NaturalCmsResourceService(
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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"name":"자료실","path":"/support/archive",
                 "parentId":40}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "new"), command);

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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"name":"회사","path":"/company","position":1}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "new"), command);

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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"position":1}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "12"), command);

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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"position":9}}
                """);

        resources.apply(new NaturalCmsContract.ResourceRef("MENU", "11"), command);

        verify(cms).updateMenu(11, "회사 소개", "/about/company", 10L, 12, "CONTENT", 3L);
        verify(cms).updateMenu(12, "비전", "/about/vision", 10L, 11, "CONTENT", 4L);
    }

    @Test
    void deletesAMenuThroughTheExistingCascadeAndReportsTheRemovedState() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = menuTree();
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        JsonNode removed = resources.apply(
                new NaturalCmsContract.ResourceRef("MENU", "10"), command);

        assertThat(removed.path("name").asText()).isEqualTo("소개");
        verify(cms).deleteMenu(10);
    }

    @Test
    void rejectsADeleteCommandThatCarriesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = new NaturalCmsResourceService(
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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"DELETE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(
                new NaturalCmsContract.ResourceRef("MENU", "10"), command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("removes 11 menus");
        verify(cms, never()).deleteMenu(anyLong());
    }

    @Test
    void keepsCreateAndDeleteClosedForResourcesThatOnlyOpenUpdate() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = new NaturalCmsResourceService(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"CREATE","fields":{"title":"새 글","body":"본문"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("operations only: UPDATE");
    }

    @Test
    void snapshotsANewMenuWithoutReadingTheDatabase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);

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
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);

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
}
