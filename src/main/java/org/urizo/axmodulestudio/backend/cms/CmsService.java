package org.urizo.axmodulestudio.backend.cms;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local-full")
public class CmsService {

    private final JdbcTemplate jdbc;

    public CmsService(JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    public List<MemberView> members() {
        return jdbc.query("""
                SELECT account_id, login_id, display_name, role
                  FROM app.admin_account
                 ORDER BY created_at, login_id
                """, (rs, row) -> member(rs));
    }

    public MemberView member(UUID id) {
        return required(() -> jdbc.queryForObject("""
                SELECT account_id, login_id, display_name, role
                  FROM app.admin_account WHERE account_id = ?
                """, (rs, row) -> member(rs), id), "회원을 찾을 수 없습니다.");
    }

    public List<MenuView> menus() {
        return jdbc.query("""
                SELECT menu_id, menu_name, path, parent_menu_id, display_order,
                       target_type, target_id
                  FROM app.cms_menu ORDER BY display_order, menu_id
                """, (rs, row) -> menu(rs));
    }

    @Transactional(transactionManager = "productTransactionManager")
    public MenuView createMenu(
            String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        requireText(name, "메뉴명");
        if (path == null || !path.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경로는 /로 시작해야 합니다.");
        }
        if (parentId != null) menu(parentId);
        String target = validateTarget(targetType, targetId);
        Long id = jdbc.queryForObject("""
                INSERT INTO app.cms_menu(
                    menu_name, path, parent_menu_id, display_order, target_type, target_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING menu_id
                """, Long.class, name.trim(), path.trim(), parentId, displayOrder, target, targetId);
        return menu(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public MenuView updateMenu(
            long id, String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        menu(id);
        requireText(name, "메뉴명");
        if (path == null || !path.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경로는 /로 시작해야 합니다.");
        }
        if (parentId != null) {
            if (parentId == id) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신을 상위 메뉴로 지정할 수 없습니다.");
            }
            menu(parentId);
        }
        String target = validateTarget(targetType, targetId);
        jdbc.update("""
                UPDATE app.cms_menu
                   SET menu_name = ?, path = ?, parent_menu_id = ?, display_order = ?,
                       target_type = ?, target_id = ?
                 WHERE menu_id = ?
                """, name.trim(), path.trim(), parentId, displayOrder, target, targetId, id);
        return menu(id);
    }

    public MenuView menu(long id) {
        return required(() -> jdbc.queryForObject("""
                SELECT menu_id, menu_name, path, parent_menu_id, display_order,
                       target_type, target_id
                  FROM app.cms_menu WHERE menu_id = ?
                """, (rs, row) -> menu(rs), id), "메뉴를 찾을 수 없습니다.");
    }

    @Transactional(transactionManager = "productTransactionManager")
    public void deleteMenu(long id) {
        if (jdbc.update("DELETE FROM app.cms_menu WHERE menu_id = ?", id) == 0) {
            throw notFound("메뉴를 찾을 수 없습니다.");
        }
    }

    public List<ContentView> contents() {
        return jdbc.query(contentSelect() + " WHERE c.deleted_yn = 'N' ORDER BY c.created_at DESC",
                (rs, row) -> content(rs));
    }

    public ContentView content(long id) {
        return required(() -> jdbc.queryForObject(
                contentSelect() + " WHERE c.content_id = ? AND c.deleted_yn = 'N'",
                (rs, row) -> content(rs), id), "콘텐츠를 찾을 수 없습니다.");
    }

    @Transactional(transactionManager = "productTransactionManager")
    public ContentView createContent(UUID authorId, String title, String body) {
        requireText(title, "제목");
        requireText(body, "내용");
        Long id = jdbc.queryForObject("""
                INSERT INTO app.cms_content(author_id, title, body)
                VALUES (?, ?, ?) RETURNING content_id
                """, Long.class, authorId, title.trim(), body);
        return content(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public ContentView updateContent(long id, String title, String body) {
        requireText(title, "제목");
        requireText(body, "내용");
        if (jdbc.update("""
                UPDATE app.cms_content SET title = ?, body = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE content_id = ? AND deleted_yn = 'N'
                """, title.trim(), body, id) == 0) throw notFound("콘텐츠를 찾을 수 없습니다.");
        return content(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public void deleteContent(long id) {
        if (jdbc.update("""
                UPDATE app.cms_content SET deleted_yn = 'Y', deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE content_id = ? AND deleted_yn = 'N'
                """, id) == 0) throw notFound("콘텐츠를 찾을 수 없습니다.");
        jdbc.update("""
                UPDATE app.cms_menu SET target_type = 'NONE', target_id = NULL
                 WHERE target_type = 'CONTENT' AND target_id = ?
                """, id);
    }

    public List<BoardView> boards() {
        return jdbc.query("""
                SELECT board_id, board_name, description, created_at, updated_at
                  FROM app.cms_board WHERE deleted_yn = 'N' ORDER BY created_at
                """, (rs, row) -> board(rs));
    }

    public BoardView board(long id) {
        return required(() -> jdbc.queryForObject("""
                SELECT board_id, board_name, description, created_at, updated_at
                  FROM app.cms_board WHERE board_id = ? AND deleted_yn = 'N'
                """, (rs, row) -> board(rs), id), "게시판을 찾을 수 없습니다.");
    }

    @Transactional(transactionManager = "productTransactionManager")
    public BoardView createBoard(String name, String description) {
        requireText(name, "게시판명");
        Long id = jdbc.queryForObject("""
                INSERT INTO app.cms_board(board_name, description)
                VALUES (?, ?) RETURNING board_id
                """, Long.class, name.trim(), description == null ? "" : description.trim());
        return board(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public BoardView updateBoard(long id, String name, String description) {
        requireText(name, "게시판명");
        if (jdbc.update("""
                UPDATE app.cms_board SET board_name = ?, description = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE board_id = ? AND deleted_yn = 'N'
                """, name.trim(), description == null ? "" : description.trim(), id) == 0) {
            throw notFound("게시판을 찾을 수 없습니다.");
        }
        return board(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public void deleteBoard(long id) {
        board(id);
        jdbc.update("""
                UPDATE app.cms_post SET deleted_yn = 'Y', deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE board_id = ? AND deleted_yn = 'N'
                """, id);
        jdbc.update("""
                UPDATE app.cms_board SET deleted_yn = 'Y', deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP WHERE board_id = ?
                """, id);
        jdbc.update("""
                UPDATE app.cms_menu SET target_type = 'NONE', target_id = NULL
                 WHERE target_type = 'BOARD' AND target_id = ?
                """, id);
    }

    public List<PostView> posts(long boardId) {
        board(boardId);
        return jdbc.query(postSelect() + " WHERE p.board_id = ? AND p.deleted_yn = 'N'"
                        + " ORDER BY p.created_at DESC",
                (rs, row) -> post(rs), boardId);
    }

    public PostView post(long id) {
        return required(() -> jdbc.queryForObject(
                postSelect() + " WHERE p.post_id = ? AND p.deleted_yn = 'N'",
                (rs, row) -> post(rs), id), "게시물을 찾을 수 없습니다.");
    }

    @Transactional(transactionManager = "productTransactionManager")
    public PostView createPost(UUID authorId, long boardId, String title, String body) {
        board(boardId);
        requireText(title, "제목");
        requireText(body, "내용");
        Long id = jdbc.queryForObject("""
                INSERT INTO app.cms_post(board_id, author_id, title, body)
                VALUES (?, ?, ?, ?) RETURNING post_id
                """, Long.class, boardId, authorId, title.trim(), body);
        return post(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public PostView updatePost(long id, String title, String body) {
        requireText(title, "제목");
        requireText(body, "내용");
        if (jdbc.update("""
                UPDATE app.cms_post SET title = ?, body = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE post_id = ? AND deleted_yn = 'N'
                """, title.trim(), body, id) == 0) throw notFound("게시물을 찾을 수 없습니다.");
        return post(id);
    }

    @Transactional(transactionManager = "productTransactionManager")
    public void deletePost(long id) {
        if (jdbc.update("""
                UPDATE app.cms_post SET deleted_yn = 'Y', deleted_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE post_id = ? AND deleted_yn = 'N'
                """, id) == 0) throw notFound("게시물을 찾을 수 없습니다.");
    }

    public List<TemplateView> templates() {
        return jdbc.query("""
                SELECT template_key, layout, primary_color, site_name, header_text,
                       footer_text, hero_image_url, hero_title, hero_subtitle,
                       hero_button_label, hero_button_url, active_yn, updated_at
                  FROM app.cms_template ORDER BY template_key
                """, (rs, row) -> template(rs));
    }

    public TemplateView activeTemplate() {
        return required(() -> jdbc.queryForObject("""
                SELECT template_key, layout, primary_color, site_name, header_text,
                       footer_text, hero_image_url, hero_title, hero_subtitle,
                       hero_button_label, hero_button_url, active_yn, updated_at
                  FROM app.cms_template WHERE active_yn = 'Y'
                """, (rs, row) -> template(rs)), "활성 템플릿을 찾을 수 없습니다.");
    }

    @Transactional(transactionManager = "productTransactionManager")
    public TemplateView saveTemplate(
            String key, String layout, String color, String siteName, String header, String footer,
            String heroImageUrl, String heroTitle, String heroSubtitle,
            String heroButtonLabel, String heroButtonUrl) {
        requireText(layout, "레이아웃");
        requireText(color, "색상");
        requireText(siteName, "사이트명");
        requireText(heroImageUrl, "메인 이미지");
        requireText(heroTitle, "메인 문구");
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "색상은 #RRGGBB 형식이어야 합니다.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM app.cms_template WHERE template_key = ?",
                Integer.class, key) == 0) throw notFound("템플릿을 찾을 수 없습니다.");
        jdbc.update("UPDATE app.cms_template SET active_yn = 'N' WHERE active_yn = 'Y'");
        jdbc.update("""
                UPDATE app.cms_template
                   SET layout = ?, primary_color = ?, site_name = ?, header_text = ?,
                       footer_text = ?, hero_image_url = ?, hero_title = ?, hero_subtitle = ?,
                       hero_button_label = ?, hero_button_url = ?, active_yn = 'Y',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE template_key = ?
                """, layout.trim(), color.toUpperCase(), siteName.trim(),
                text(header), text(footer), heroImageUrl.trim(), heroTitle.trim(),
                text(heroSubtitle), text(heroButtonLabel), text(heroButtonUrl), key);
        return activeTemplate();
    }

    @Transactional(transactionManager = "productTransactionManager")
    public void ensureDemoData() {
        List<UUID> authors = jdbc.query("""
                SELECT account_id FROM app.admin_account
                 WHERE role = 'SUPER_ADMIN' ORDER BY created_at LIMIT 1
                """, (rs, row) -> rs.getObject("account_id", UUID.class));
        if (authors.isEmpty()) return;
        UUID author = authors.get(0);

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

        mapMenu("/about/company", "CONTENT", company);
        mapMenu("/about/vision", "CONTENT", vision);
        mapMenu("/products/ax-module-studio", "CONTENT", product);
        mapMenu("/products/solutions", "CONTENT", solutions);
        mapMenu("/services/consulting", "CONTENT", consulting);
        mapMenu("/services/technical-support", "CONTENT", support);
        mapMenu("/support/notices", "BOARD", notices);
        mapMenu("/support/contact", "CONTENT", contact);
    }

    private static String contentSelect() {
        return """
                SELECT c.content_id, c.author_id, a.display_name AS author_name, c.title, c.body,
                       c.created_at, c.updated_at
                  FROM app.cms_content c JOIN app.admin_account a ON a.account_id = c.author_id
                """;
    }

    private static String postSelect() {
        return """
                SELECT p.post_id, p.board_id, p.author_id, a.display_name AS author_name,
                       p.title, p.body, p.created_at, p.updated_at
                  FROM app.cms_post p JOIN app.admin_account a ON a.account_id = p.author_id
                """;
    }

    private static MemberView member(ResultSet rs) throws SQLException {
        return new MemberView(rs.getObject("account_id", UUID.class), rs.getString("login_id"),
                rs.getString("display_name"), rs.getString("role"));
    }

    private static MenuView menu(ResultSet rs) throws SQLException {
        Long parent = rs.getObject("parent_menu_id", Long.class);
        Long target = rs.getObject("target_id", Long.class);
        return new MenuView(rs.getLong("menu_id"), rs.getString("menu_name"),
                rs.getString("path"), parent, rs.getInt("display_order"),
                rs.getString("target_type"), target);
    }

    private static ContentView content(ResultSet rs) throws SQLException {
        return new ContentView(rs.getLong("content_id"), rs.getObject("author_id", UUID.class),
                rs.getString("author_name"), rs.getString("title"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static BoardView board(ResultSet rs) throws SQLException {
        return new BoardView(rs.getLong("board_id"), rs.getString("board_name"),
                rs.getString("description"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static PostView post(ResultSet rs) throws SQLException {
        return new PostView(rs.getLong("post_id"), rs.getLong("board_id"),
                rs.getObject("author_id", UUID.class), rs.getString("author_name"),
                rs.getString("title"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static TemplateView template(ResultSet rs) throws SQLException {
        return new TemplateView(rs.getString("template_key"), rs.getString("layout"),
                rs.getString("primary_color"), rs.getString("site_name"),
                rs.getString("header_text"), rs.getString("footer_text"),
                rs.getString("hero_image_url"), rs.getString("hero_title"),
                rs.getString("hero_subtitle"), rs.getString("hero_button_label"),
                rs.getString("hero_button_url"),
                "Y".equals(rs.getString("active_yn")), rs.getTimestamp("updated_at").toInstant());
    }

    private String validateTarget(String targetType, Long targetId) {
        String target = targetType == null ? "NONE" : targetType.trim().toUpperCase();
        if ("NONE".equals(target)) {
            if (targetId != null) throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "연결 없음 메뉴에는 연결 대상을 지정할 수 없습니다.");
            return target;
        }
        if (targetId == null) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "메뉴 연결 대상을 선택해야 합니다.");
        if ("CONTENT".equals(target)) content(targetId);
        else if ("BOARD".equals(target)) board(targetId);
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 메뉴 연결 유형입니다.");
        return target;
    }

    private long ensureContent(UUID author, String title, String body) {
        List<Long> ids = jdbc.query("""
                SELECT content_id FROM app.cms_content
                 WHERE title = ? AND deleted_yn = 'N' ORDER BY content_id LIMIT 1
                """, (rs, row) -> rs.getLong("content_id"), title);
        if (!ids.isEmpty()) return ids.get(0);
        return jdbc.queryForObject("""
                INSERT INTO app.cms_content(author_id, title, body)
                VALUES (?, ?, ?) RETURNING content_id
                """, Long.class, author, title, body);
    }

    private long ensureBoard(String name, String description) {
        List<Long> ids = jdbc.query("""
                SELECT board_id FROM app.cms_board
                 WHERE board_name = ? AND deleted_yn = 'N' ORDER BY board_id LIMIT 1
                """, (rs, row) -> rs.getLong("board_id"), name);
        if (!ids.isEmpty()) return ids.get(0);
        return jdbc.queryForObject("""
                INSERT INTO app.cms_board(board_name, description)
                VALUES (?, ?) RETURNING board_id
                """, Long.class, name, description);
    }

    private void ensurePost(UUID author, long boardId, String title, String body) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM app.cms_post
                 WHERE board_id = ? AND title = ? AND deleted_yn = 'N'
                """, Integer.class, boardId, title);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO app.cms_post(board_id, author_id, title, body)
                    VALUES (?, ?, ?, ?)
                    """, boardId, author, title, body);
        }
    }

    private void mapMenu(String path, String targetType, long targetId) {
        jdbc.update("""
                UPDATE app.cms_menu SET target_type = ?, target_id = ? WHERE path = ?
                """, targetType, targetId, path);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "은(는) 필수입니다.");
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static <T> T required(Query<T> query, String message) {
        try {
            T value = query.get();
            if (value == null) throw notFound(message);
            return value;
        } catch (EmptyResultDataAccessException failure) {
            throw notFound(message);
        }
    }

    @FunctionalInterface
    private interface Query<T> { T get(); }

    public record MemberView(UUID id, String loginId, String name, String role) {}
    public record MenuView(long id, String name, String path, Long parentId, int displayOrder,
                           String targetType, Long targetId) {}
    public record ContentView(long id, UUID authorId, String authorName, String title, String body,
                              Instant createdAt, Instant updatedAt) {}
    public record BoardView(long id, String name, String description,
                            Instant createdAt, Instant updatedAt) {}
    public record PostView(long id, long boardId, UUID authorId, String authorName, String title,
                           String body, Instant createdAt, Instant updatedAt) {}
    public record TemplateView(String key, String layout, String primaryColor, String siteName,
                               String headerText, String footerText, String heroImageUrl,
                               String heroTitle, String heroSubtitle, String heroButtonLabel,
                               String heroButtonUrl, boolean active, Instant updatedAt) {}
}
