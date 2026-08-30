package org.urizo.axmodulestudio.backend.cms.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.repository.AdminAccountRepository;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MemberView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PostView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.entity.CmsBoardEntity;
import org.urizo.axmodulestudio.backend.cms.entity.CmsContentEntity;
import org.urizo.axmodulestudio.backend.cms.entity.CmsMenuEntity;
import org.urizo.axmodulestudio.backend.cms.entity.CmsPostEntity;
import org.urizo.axmodulestudio.backend.cms.entity.CmsTemplateEntity;

/** Keeps the CMS service contract small while delegating persistence to Spring Data JPA. */
@Repository
@Profile("local-full")
public class CmsRepository {

    private static final String ACTIVE = "Y";
    private static final String NOT_DELETED = "N";

    private final AdminAccountRepository accountRepository;
    private final CmsMenuJpaRepository menuRepository;
    private final CmsContentJpaRepository contentRepository;
    private final CmsBoardJpaRepository boardRepository;
    private final CmsPostJpaRepository postRepository;
    private final CmsTemplateJpaRepository templateRepository;

    public CmsRepository(
            AdminAccountRepository accountRepository,
            CmsMenuJpaRepository menuRepository,
            CmsContentJpaRepository contentRepository,
            CmsBoardJpaRepository boardRepository,
            CmsPostJpaRepository postRepository,
            CmsTemplateJpaRepository templateRepository) {
        this.accountRepository = accountRepository;
        this.menuRepository = menuRepository;
        this.contentRepository = contentRepository;
        this.boardRepository = boardRepository;
        this.postRepository = postRepository;
        this.templateRepository = templateRepository;
    }

    public List<MemberView> findMembers() {
        Sort order = Sort.by("createdAt").ascending().and(Sort.by("loginId").ascending());
        return accountRepository.findAll(order).stream().map(CmsRepository::member).toList();
    }

    public Optional<MemberView> findMember(UUID id) {
        return accountRepository.findById(id).map(CmsRepository::member);
    }

    public List<MenuView> findMenus() {
        return menuRepository.findAllByOrderByDisplayOrderAscMenuIdAsc().stream()
                .map(CmsRepository::menu)
                .toList();
    }

    public Optional<MenuView> findMenu(long id) {
        return menuRepository.findById(id).map(CmsRepository::menu);
    }

    public List<MenuView> findChildMenus(long parentId) {
        return menuRepository.findAllByParentMenuIdOrderByDisplayOrderAscMenuIdAsc(parentId).stream()
                .map(CmsRepository::menu)
                .toList();
    }

    public long insertMenu(
            String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        CmsMenuEntity menu = menuRepository.save(
                new CmsMenuEntity(name, path, parentId, displayOrder, targetType, targetId));
        return menu.getMenuId();
    }

    public int updateMenu(
            long id, String name, String path, Long parentId, int displayOrder,
            String targetType, Long targetId) {
        return menuRepository.findById(id).map(menu -> {
            menu.change(name, path, parentId, displayOrder, targetType, targetId);
            return 1;
        }).orElse(0);
    }

    public int deleteMenu(long id) {
        return menuRepository.findById(id).map(menu -> {
            menuRepository.delete(menu);
            return 1;
        }).orElse(0);
    }

    public List<ContentView> findContents() {
        return contentRepository.findAllByDeletedYnOrderByCreatedAtDesc(NOT_DELETED).stream()
                .map(CmsRepository::content)
                .toList();
    }

    public Optional<ContentView> findContent(long id) {
        return findContentEntity(id).map(CmsRepository::content);
    }

    public long insertContent(UUID authorId, String title, String body) {
        AdminAccountEntity author = accountRepository.getReferenceById(authorId);
        CmsContentEntity content = contentRepository.save(
                new CmsContentEntity(author, title, body, Instant.now()));
        return content.getContentId();
    }

    public int updateContent(long id, String title, String body) {
        return findContentEntity(id).map(content -> {
            content.change(title, body, Instant.now());
            return 1;
        }).orElse(0);
    }

    public int softDeleteContent(long id) {
        return findContentEntity(id).map(content -> {
            content.softDelete(Instant.now());
            return 1;
        }).orElse(0);
    }

    public List<BoardView> findBoards() {
        return boardRepository.findAllByDeletedYnOrderByCreatedAtAsc(NOT_DELETED).stream()
                .map(CmsRepository::board)
                .toList();
    }

    public Optional<BoardView> findBoard(long id) {
        return findBoardEntity(id).map(CmsRepository::board);
    }

    public long insertBoard(String name, String description) {
        CmsBoardEntity board = boardRepository.save(
                new CmsBoardEntity(name, description, Instant.now()));
        return board.getBoardId();
    }

    public int updateBoard(long id, String name, String description) {
        return findBoardEntity(id).map(board -> {
            board.change(name, description, Instant.now());
            return 1;
        }).orElse(0);
    }

    public void softDeletePostsByBoard(long boardId) {
        Instant deletedAt = Instant.now();
        postRepository.findAllByBoard_BoardIdAndDeletedYnOrderByCreatedAtDesc(
                boardId, NOT_DELETED).forEach(post -> post.softDelete(deletedAt));
    }

    public int softDeleteBoard(long id) {
        return findBoardEntity(id).map(board -> {
            board.softDelete(Instant.now());
            return 1;
        }).orElse(0);
    }

    public List<PostView> findPosts(long boardId) {
        return postRepository.findAllByBoard_BoardIdAndDeletedYnOrderByCreatedAtDesc(
                        boardId, NOT_DELETED).stream()
                .map(CmsRepository::post)
                .toList();
    }

    public Optional<PostView> findPost(long id) {
        return findPostEntity(id).map(CmsRepository::post);
    }

    public long insertPost(UUID authorId, long boardId, String title, String body) {
        AdminAccountEntity author = accountRepository.getReferenceById(authorId);
        CmsBoardEntity board = boardRepository.getReferenceById(boardId);
        CmsPostEntity post = postRepository.save(
                new CmsPostEntity(board, author, title, body, Instant.now()));
        return post.getPostId();
    }

    public int updatePost(long id, String title, String body) {
        return findPostEntity(id).map(post -> {
            post.change(title, body, Instant.now());
            return 1;
        }).orElse(0);
    }

    public int softDeletePost(long id) {
        return findPostEntity(id).map(post -> {
            post.softDelete(Instant.now());
            return 1;
        }).orElse(0);
    }

    public List<TemplateView> findTemplates() {
        return templateRepository.findAllByOrderByTemplateKeyAsc().stream()
                .map(CmsRepository::template)
                .toList();
    }

    public Optional<TemplateView> findActiveTemplate() {
        return templateRepository.findFirstByActiveYn(ACTIVE).map(CmsRepository::template);
    }

    public boolean templateExists(String key) {
        return templateRepository.existsById(key);
    }

    public void deactivateTemplates() {
        templateRepository.findAllByActiveYn(ACTIVE).forEach(CmsTemplateEntity::deactivate);
        templateRepository.flush();
    }

    public int updateTemplate(
            String key, String layout, String color, String siteName, String header, String footer,
            String heroImageUrl, String heroTitle, String heroSubtitle,
            String heroButtonLabel, String heroButtonUrl) {
        return templateRepository.findById(key).map(template -> {
            template.activate(layout, color, siteName, header, footer, heroImageUrl, heroTitle,
                    heroSubtitle, heroButtonLabel, heroButtonUrl, Instant.now());
            return 1;
        }).orElse(0);
    }

    public Optional<UUID> findSuperAdminAuthor() {
        Sort order = Sort.by("createdAt").ascending();
        return accountRepository.findAll(order).stream()
                .filter(account -> account.getRole() == AdminRole.SUPER_ADMIN)
                .map(AdminAccountEntity::getAccountId)
                .findFirst();
    }

    public Optional<Long> findContentIdByTitle(String title) {
        return contentRepository.findFirstByTitleAndDeletedYnOrderByContentIdAsc(
                        title, NOT_DELETED)
                .map(CmsContentEntity::getContentId);
    }

    public Optional<Long> findBoardIdByName(String name) {
        return boardRepository.findFirstByBoardNameAndDeletedYnOrderByBoardIdAsc(
                        name, NOT_DELETED)
                .map(CmsBoardEntity::getBoardId);
    }

    public boolean postExists(long boardId, String title) {
        return postRepository.existsByBoard_BoardIdAndTitleAndDeletedYn(
                boardId, title, NOT_DELETED);
    }

    public void clearMenuTarget(String targetType, long targetId) {
        menuRepository.findAllByTargetTypeAndTargetId(targetType, targetId)
                .forEach(menu -> menu.mapTo("NONE", null));
    }

    public void mapMenu(String path, String targetType, long targetId) {
        menuRepository.findAllByPath(path)
                .forEach(menu -> menu.mapTo(targetType, targetId));
    }

    private Optional<CmsContentEntity> findContentEntity(long id) {
        return contentRepository.findByContentIdAndDeletedYn(id, NOT_DELETED);
    }

    private Optional<CmsBoardEntity> findBoardEntity(long id) {
        return boardRepository.findByBoardIdAndDeletedYn(id, NOT_DELETED);
    }

    private Optional<CmsPostEntity> findPostEntity(long id) {
        return postRepository.findByPostIdAndDeletedYn(id, NOT_DELETED);
    }

    private static MemberView member(AdminAccountEntity account) {
        return new MemberView(account.getAccountId(), account.getLoginId(),
                account.getDisplayName(), account.getRole().name());
    }

    private static MenuView menu(CmsMenuEntity menu) {
        return new MenuView(menu.getMenuId(), menu.getMenuName(), menu.getPath(),
                menu.getParentMenuId(), menu.getDisplayOrder(), menu.getTargetType(),
                menu.getTargetId());
    }

    private static ContentView content(CmsContentEntity content) {
        AdminAccountEntity author = content.getAuthor();
        return new ContentView(content.getContentId(), author.getAccountId(),
                author.getDisplayName(), content.getTitle(), content.getBody(),
                content.getCreatedAt(), content.getUpdatedAt());
    }

    private static BoardView board(CmsBoardEntity board) {
        return new BoardView(board.getBoardId(), board.getBoardName(), board.getDescription(),
                board.getCreatedAt(), board.getUpdatedAt());
    }

    private static PostView post(CmsPostEntity post) {
        AdminAccountEntity author = post.getAuthor();
        return new PostView(post.getPostId(), post.getBoard().getBoardId(),
                author.getAccountId(), author.getDisplayName(), post.getTitle(), post.getBody(),
                post.getCreatedAt(), post.getUpdatedAt());
    }

    private static TemplateView template(CmsTemplateEntity template) {
        return new TemplateView(template.getTemplateKey(), template.getLayout(),
                template.getPrimaryColor(), template.getSiteName(), template.getHeaderText(),
                template.getFooterText(), template.getHeroImageUrl(), template.getHeroTitle(),
                template.getHeroSubtitle(), template.getHeroButtonLabel(),
                template.getHeroButtonUrl(), template.isActive(), template.getUpdatedAt());
    }
}
