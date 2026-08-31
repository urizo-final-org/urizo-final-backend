package org.urizo.axmodulestudio.backend.cms.service;

import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.invalidRequest;
import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.notFound;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MemberView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PostView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;

@Service
@Profile("local-full")
public class CmsService {

    private final CmsRepository repository;
    private final CmsSiteSettingsService siteSettings;

    public CmsService(CmsRepository repository, CmsSiteSettingsService siteSettings) {
        this.repository = repository;
        this.siteSettings = siteSettings;
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<MemberView> members() {
        return repository.findMembers();
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public MemberView member(UUID id) {
        return repository.findMember(id).orElseThrow(() -> notFound("회원을 찾을 수 없습니다."));
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<MenuView> menus() {
        return repository.findMenus();
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public MenuView createMenu(
            String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        validateMenu(name, path, parentId, null);
        String target = validateTarget(targetType, targetId);
        long id = repository.insertMenu(
                name.trim(), path.trim(), parentId, displayOrder, target, targetId);
        return menu(id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public MenuView updateMenu(
            long id, String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        menu(id);
        validateMenu(name, path, parentId, id);
        String target = validateTarget(targetType, targetId);
        repository.updateMenu(
                id, name.trim(), path.trim(), parentId, displayOrder, target, targetId);
        return menu(id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public MenuView menu(long id) {
        return repository.findMenu(id).orElseThrow(() -> notFound("메뉴를 찾을 수 없습니다."));
    }

    /**
     * 하위메뉴를 먼저 지운 뒤 대상 메뉴를 지운다.
     *
     * <p>FK가 {@code ON DELETE SET NULL}이라 부모를 먼저 지우면 하위의 상위 메뉴가 비면서
     * 대메뉴로 승격된다. 하위를 앞서 지우면 그 경로를 밟지 않는다. 계층이 2단계라 한 번만 조회한다.
     */
    @Transactional(transactionManager = "authJpaTransactionManager")
    public void deleteMenu(long id) {
        menu(id);
        for (MenuView child : repository.findChildMenus(id)) {
            repository.deleteMenu(child.id());
        }
        if (repository.deleteMenu(id) == 0) {
            throw notFound("메뉴를 찾을 수 없습니다.");
        }
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<ContentView> contents() {
        return repository.findContents();
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public ContentView content(long id) {
        return repository.findContent(id).orElseThrow(() -> notFound("콘텐츠를 찾을 수 없습니다."));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public ContentView createContent(UUID authorId, String title, String body) {
        validateArticle(title, body);
        return content(repository.insertContent(authorId, title.trim(), body));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public ContentView updateContent(long id, String title, String body) {
        validateArticle(title, body);
        if (repository.updateContent(id, title.trim(), body) == 0) {
            throw notFound("콘텐츠를 찾을 수 없습니다.");
        }
        return content(id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public void deleteContent(long id) {
        if (repository.softDeleteContent(id) == 0) {
            throw notFound("콘텐츠를 찾을 수 없습니다.");
        }
        repository.clearMenuTarget("CONTENT", id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<BoardView> boards() {
        return repository.findBoards();
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public BoardView board(long id) {
        return repository.findBoard(id).orElseThrow(() -> notFound("게시판을 찾을 수 없습니다."));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public BoardView createBoard(String name, String description) {
        validateBoard(name);
        return board(repository.insertBoard(name.trim(), text(description)));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public BoardView updateBoard(long id, String name, String description) {
        validateBoard(name);
        if (repository.updateBoard(id, name.trim(), text(description)) == 0) {
            throw notFound("게시판을 찾을 수 없습니다.");
        }
        return board(id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public void deleteBoard(long id) {
        board(id);
        repository.softDeletePostsByBoard(id);
        if (repository.softDeleteBoard(id) == 0) {
            throw notFound("게시판을 찾을 수 없습니다.");
        }
        repository.clearMenuTarget("BOARD", id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<PostView> posts(long boardId) {
        board(boardId);
        return repository.findPosts(boardId);
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public PostView post(long id) {
        return repository.findPost(id).orElseThrow(() -> notFound("게시물을 찾을 수 없습니다."));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public PostView createPost(UUID authorId, long boardId, String title, String body) {
        board(boardId);
        validateArticle(title, body);
        return post(repository.insertPost(authorId, boardId, title.trim(), body));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public PostView updatePost(long id, String title, String body) {
        validateArticle(title, body);
        if (repository.updatePost(id, title.trim(), body) == 0) {
            throw notFound("게시물을 찾을 수 없습니다.");
        }
        return post(id);
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public void deletePost(long id) {
        if (repository.softDeletePost(id) == 0) {
            throw notFound("게시물을 찾을 수 없습니다.");
        }
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<TemplateView> templates() {
        return repository.findTemplates();
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public TemplateView activeTemplate() {
        return repository.findActiveTemplate()
                .orElseThrow(() -> notFound("활성 템플릿을 찾을 수 없습니다."));
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public TemplateView saveTemplate(
            String key, String layout, String color, String siteName, String header, String footer,
            String heroImageUrl, String heroTitle, String heroSubtitle,
            String heroButtonLabel, String heroButtonUrl) {
        validateTemplate(layout, color, siteName, heroImageUrl, heroTitle);
        if (!repository.templateExists(key)) {
            throw notFound("템플릿을 찾을 수 없습니다.");
        }
        repository.deactivateTemplates();
        repository.updateTemplate(
                key, layout.trim(), color.toUpperCase(), siteName.trim(), text(header), text(footer),
                heroImageUrl.trim(), heroTitle.trim(), text(heroSubtitle),
                text(heroButtonLabel), text(heroButtonUrl));
        siteSettings.applyTemplateToDefaultSite(key);
        return activeTemplate();
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public void ensureDemoData() {
        repository.findSuperAdminAuthor().ifPresent(this::ensureDemoData);
    }

    private void ensureDemoData(UUID author) {
        long company = ensureContent(author, "회사 소개",
                "## 사람과 기술을 연결합니다\n\nAX Bio Studio는 복잡한 업무를 단순한 디지털 경험으로 바꾸는 기술 기업입니다.\n\n- 신뢰할 수 있는 기술\n- 현장 중심의 서비스\n- 함께 성장하는 파트너십");
        long vision = ensureContent(author, "비전",
                "## Technology for a Better Tomorrow\n\n기술이 사람의 가능성을 넓히고 지속 가능한 성장을 만드는 미래를 준비합니다.");
        long product = ensureContent(author, "AX Module Studio",
                "## AX Module Studio\n\n아이디어부터 실행까지 연결하는 모듈형 AI 업무 플랫폼입니다.");
        long solutions = ensureContent(author, "Solutions",
                "## Business Solutions\n\n조직의 업무 흐름에 맞춘 간결하고 실용적인 디지털 솔루션을 제공합니다.");
        long consulting = ensureContent(author, "Consulting",
                "## Consulting\n\n현황 진단부터 적용 계획까지 핵심 과제에 집중한 컨설팅을 제공합니다.");
        long support = ensureContent(author, "Technical Support",
                "## Technical Support\n\n안정적인 서비스 운영을 위한 기술 지원과 가이드를 제공합니다.");
        long contact = ensureContent(author, "문의하기",
                "## 문의하기\n\n대표전화 02-1234-5678\n\n이메일 hello@axbiostudio.example");
        long notices = ensureBoard("공지사항", "AX Bio Studio의 새로운 소식을 전합니다.");
        ensurePost(author, notices, "AX Bio Studio 홈페이지를 새롭게 열었습니다.",
                "더 나은 사용자 경험과 새로운 소식으로 찾아뵙겠습니다.");
        ensurePost(author, notices, "AX Module Studio 데모 안내",
                "메뉴, 콘텐츠, 게시판과 템플릿이 연결되는 간단한 CMS 데모입니다.");

        repository.mapMenu("/about/company", "CONTENT", company);
        repository.mapMenu("/about/vision", "CONTENT", vision);
        repository.mapMenu("/products/ax-module-studio", "CONTENT", product);
        repository.mapMenu("/products/solutions", "CONTENT", solutions);
        repository.mapMenu("/services/consulting", "CONTENT", consulting);
        repository.mapMenu("/services/technical-support", "CONTENT", support);
        repository.mapMenu("/support/notices", "BOARD", notices);
        repository.mapMenu("/support/contact", "CONTENT", contact);
    }

    private void validateMenu(String name, String path, Long parentId, Long currentId) {
        requireText(name, "메뉴명");
        if (path == null || !path.startsWith("/")) {
            throw invalidRequest("경로는 /로 시작해야 합니다.");
        }
        if (parentId == null) {
            return;
        }
        if (parentId.equals(currentId)) {
            throw invalidRequest("자기 자신을 상위 메뉴로 지정할 수 없습니다.");
        }
        menu(parentId);
    }

    private String validateTarget(String targetType, Long targetId) {
        String target = targetType == null ? "NONE" : targetType.trim().toUpperCase();
        if ("NONE".equals(target)) {
            if (targetId != null) {
                throw invalidRequest("연결 없음 메뉴에는 연결 대상을 지정할 수 없습니다.");
            }
            return target;
        }
        if (targetId == null) {
            throw invalidRequest("메뉴 연결 대상을 선택해야 합니다.");
        }
        if ("CONTENT".equals(target)) {
            content(targetId);
        }
        else if ("BOARD".equals(target)) {
            board(targetId);
        }
        else {
            throw invalidRequest("지원하지 않는 메뉴 연결 유형입니다.");
        }
        return target;
    }

    private void validateArticle(String title, String body) {
        requireText(title, "제목");
        requireText(body, "내용");
    }

    private void validateBoard(String name) {
        requireText(name, "게시판명");
    }

    private void validateTemplate(
            String layout, String color, String siteName, String heroImageUrl, String heroTitle) {
        requireText(layout, "레이아웃");
        requireText(color, "색상");
        requireText(siteName, "사이트명");
        requireText(heroImageUrl, "메인 이미지");
        requireText(heroTitle, "메인 문구");
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            throw invalidRequest("색상은 #RRGGBB 형식이어야 합니다.");
        }
    }

    private long ensureContent(UUID author, String title, String body) {
        return repository.findContentIdByTitle(title)
                .orElseGet(() -> repository.insertContent(author, title, body));
    }

    private long ensureBoard(String name, String description) {
        return repository.findBoardIdByName(name)
                .orElseGet(() -> repository.insertBoard(name, description));
    }

    private void ensurePost(UUID author, long boardId, String title, String body) {
        if (!repository.postExists(boardId, title)) {
            repository.insertPost(author, boardId, title, body);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidRequest(field + "은(는) 필수입니다.");
        }
    }
}
