package org.urizo.axmodulestudio.backend.cms.controller;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.governance.CmsChangeRecorder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.ArticleRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.BoardRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.PostRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.MenuRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.TemplateRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentImageView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MemberView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PostView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;

@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api/cms")
public class CmsAdminController {

    private final CmsService cms;
    private final AuthService authService;
    private final CmsChangeRecorder history;

    public CmsAdminController(CmsService cms, AuthService authService, CmsChangeRecorder history) {
        this.cms = cms;
        this.authService = authService;
        this.history = history;
    }

    @GetMapping("/members")
    List<MemberView> members() {
        return cms.members();
    }

    @GetMapping("/members/{id}")
    MemberView member(@PathVariable UUID id) {
        return cms.member(id);
    }

    @GetMapping("/menus")
    List<MenuView> menus() {
        return cms.menus();
    }

    @PostMapping("/menus")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public MenuView createMenu(Authentication authentication, @Valid @RequestBody MenuRequest request) {
        var actor = actor(authentication);
        var result = cms.createMenu(request.name(), request.path(), request.parentId(),
                request.displayOrder(), request.targetType(), request.targetId());
        history.record(actor, "MENU", String.valueOf(result.id()), "CREATE", result.name());
        return result;
    }

    @PutMapping("/menus/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public MenuView updateMenu(Authentication authentication, @PathVariable long id, @Valid @RequestBody MenuRequest request) {
        var actor = actor(authentication);
        var result = cms.updateMenu(id, request.name(), request.path(), request.parentId(),
                request.displayOrder(), request.targetType(), request.targetId());
        history.record(actor, "MENU", String.valueOf(id), "UPDATE", result.name());
        return result;
    }

    @DeleteMapping("/menus/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ResponseEntity<Void> deleteMenu(Authentication authentication, @PathVariable long id) {
        var actor = actor(authentication);
        cms.deleteMenu(id);
        history.record(actor, "MENU", String.valueOf(id), "DELETE", "메뉴 #" + id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contents")
    List<ContentView> contents() {
        return cms.contents();
    }

    @GetMapping("/contents/{id}")
    ContentView content(@PathVariable long id) {
        return cms.content(id);
    }

    @PostMapping("/contents")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ContentView createContent(
            Authentication authentication, @Valid @RequestBody ArticleRequest request) {
        var actor = actor(authentication);
        var result = cms.createContent(actor.actorId(), request.title(), request.body());
        history.record(actor, "CONTENT", String.valueOf(result.id()), "CREATE", result.title());
        return result;
    }

    @PutMapping("/contents/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ContentView updateContent(
            Authentication authentication, @PathVariable long id, @Valid @RequestBody ArticleRequest request) {
        var actor = actor(authentication);
        var result = cms.updateContent(id, request.title(), request.body());
        history.record(actor, "CONTENT", String.valueOf(id), "UPDATE", result.title());
        return result;
    }

    @DeleteMapping("/contents/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ResponseEntity<Void> deleteContent(Authentication authentication, @PathVariable long id) {
        var actor = actor(authentication);
        cms.deleteContent(id);
        history.record(actor, "CONTENT", String.valueOf(id), "DELETE", "콘텐츠 #" + id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 컨텐츠 본문에 넣을 이미지를 올린다.
     *
     * <p>경로가 {@code /api/cms}라 관리자 권한 규칙이 그대로 적용된다. 응답의 {@code id}로
     * 화면이 본문에 넣을 조회 주소를 만든다. 조회는 방문자도 봐야 하므로 {@code /api/site} 쪽에 있다.
     */
    @PostMapping("/images")
    ContentImageView uploadImage(
            Authentication authentication, @RequestParam("file") MultipartFile file)
            throws IOException {
        return cms.createContentImage(actor(authentication).actorId(), file.getBytes());
    }

    @GetMapping("/boards")
    List<BoardView> boards() {
        return cms.boards();
    }

    @GetMapping("/boards/{id}")
    BoardView board(@PathVariable long id) {
        return cms.board(id);
    }

    @PostMapping("/boards")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public BoardView createBoard(Authentication authentication, @Valid @RequestBody BoardRequest request) {
        var actor = actor(authentication);
        var result = cms.createBoard(request);
        history.record(actor, "BOARD", String.valueOf(result.id()), "CREATE", result.name());
        return result;
    }

    @PutMapping("/boards/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public BoardView updateBoard(Authentication authentication, @PathVariable long id, @Valid @RequestBody BoardRequest request) {
        var actor = actor(authentication);
        var result = cms.updateBoard(id, request);
        history.record(actor, "BOARD", String.valueOf(id), "UPDATE", result.name());
        return result;
    }

    @DeleteMapping("/boards/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ResponseEntity<Void> deleteBoard(Authentication authentication, @PathVariable long id) {
        var actor = actor(authentication);
        cms.deleteBoard(id);
        history.record(actor, "BOARD", String.valueOf(id), "DELETE", "게시판 #" + id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/boards/{boardId}/posts")
    List<PostView> posts(@PathVariable long boardId) {
        return cms.posts(boardId);
    }

    @PostMapping("/boards/{boardId}/posts")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public PostView createPost(
            Authentication authentication,
            @PathVariable long boardId,
            @Valid @RequestBody PostRequest request) {
        var actor = actor(authentication);
        var result = cms.createPost(actor.actorId(), boardId, request);
        history.record(actor, "POST", String.valueOf(result.id()), "CREATE", result.title());
        return result;
    }

    @GetMapping("/posts/{id}")
    PostView post(@PathVariable long id) {
        return cms.post(id);
    }

    @PutMapping("/posts/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public PostView updatePost(Authentication authentication, @PathVariable long id, @Valid @RequestBody PostRequest request) {
        var actor = actor(authentication);
        var result = cms.updatePost(id, request);
        history.record(actor, "POST", String.valueOf(id), "UPDATE", result.title());
        return result;
    }

    @DeleteMapping("/posts/{id}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public ResponseEntity<Void> deletePost(Authentication authentication, @PathVariable long id) {
        var actor = actor(authentication);
        cms.deletePost(id);
        history.record(actor, "POST", String.valueOf(id), "DELETE", "게시글 #" + id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/templates")
    List<TemplateView> templates() {
        return cms.templates();
    }

    @PutMapping("/templates/{key}")
    @Transactional(transactionManager = "authJpaTransactionManager")
    public TemplateView saveTemplate(
            Authentication authentication, @PathVariable String key, @Valid @RequestBody TemplateRequest request) {
        var actor = actor(authentication);
        var result = cms.saveTemplate(key, request.layout(), request.primaryColor(), request.siteName(),
                request.headerText(), request.footerText(), request.heroImageUrl(),
                request.heroTitle(), request.heroSubtitle(), request.heroButtonLabel(),
                request.heroButtonUrl());
        history.record(actor, "TEMPLATE", key, "SAVE", request.siteName());
        return result;
    }

    private AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        try {
            return authService.loadActor(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException failure) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required.", failure);
        }
    }
}
