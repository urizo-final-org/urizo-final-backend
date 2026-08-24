package org.urizo.axmodulestudio.backend.cms.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.ArticleRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.BoardRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.MenuRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.TemplateRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
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

    public CmsAdminController(CmsService cms, AuthService authService) {
        this.cms = cms;
        this.authService = authService;
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
    MenuView createMenu(@Valid @RequestBody MenuRequest request) {
        return cms.createMenu(request.name(), request.path(), request.parentId(),
                request.displayOrder(), request.targetType(), request.targetId());
    }

    @PutMapping("/menus/{id}")
    MenuView updateMenu(@PathVariable long id, @Valid @RequestBody MenuRequest request) {
        return cms.updateMenu(id, request.name(), request.path(), request.parentId(),
                request.displayOrder(), request.targetType(), request.targetId());
    }

    @DeleteMapping("/menus/{id}")
    ResponseEntity<Void> deleteMenu(@PathVariable long id) {
        cms.deleteMenu(id);
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
    ContentView createContent(
            Authentication authentication, @Valid @RequestBody ArticleRequest request) {
        return cms.createContent(actor(authentication).actorId(), request.title(), request.body());
    }

    @PutMapping("/contents/{id}")
    ContentView updateContent(
            @PathVariable long id, @Valid @RequestBody ArticleRequest request) {
        return cms.updateContent(id, request.title(), request.body());
    }

    @DeleteMapping("/contents/{id}")
    ResponseEntity<Void> deleteContent(@PathVariable long id) {
        cms.deleteContent(id);
        return ResponseEntity.noContent().build();
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
    BoardView createBoard(@Valid @RequestBody BoardRequest request) {
        return cms.createBoard(request.name(), request.description());
    }

    @PutMapping("/boards/{id}")
    BoardView updateBoard(@PathVariable long id, @Valid @RequestBody BoardRequest request) {
        return cms.updateBoard(id, request.name(), request.description());
    }

    @DeleteMapping("/boards/{id}")
    ResponseEntity<Void> deleteBoard(@PathVariable long id) {
        cms.deleteBoard(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/boards/{boardId}/posts")
    List<PostView> posts(@PathVariable long boardId) {
        return cms.posts(boardId);
    }

    @PostMapping("/boards/{boardId}/posts")
    PostView createPost(
            Authentication authentication,
            @PathVariable long boardId,
            @Valid @RequestBody ArticleRequest request) {
        return cms.createPost(
                actor(authentication).actorId(), boardId, request.title(), request.body());
    }

    @GetMapping("/posts/{id}")
    PostView post(@PathVariable long id) {
        return cms.post(id);
    }

    @PutMapping("/posts/{id}")
    PostView updatePost(@PathVariable long id, @Valid @RequestBody ArticleRequest request) {
        return cms.updatePost(id, request.title(), request.body());
    }

    @DeleteMapping("/posts/{id}")
    ResponseEntity<Void> deletePost(@PathVariable long id) {
        cms.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/templates")
    List<TemplateView> templates() {
        return cms.templates();
    }

    @PutMapping("/templates/{key}")
    TemplateView saveTemplate(
            @PathVariable String key, @Valid @RequestBody TemplateRequest request) {
        return cms.saveTemplate(key, request.layout(), request.primaryColor(), request.siteName(),
                request.headerText(), request.footerText(), request.heroImageUrl(),
                request.heroTitle(), request.heroSubtitle(), request.heroButtonLabel(),
                request.heroButtonUrl());
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
