package org.urizo.axmodulestudio.backend.cms;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
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
import org.urizo.axmodulestudio.backend.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;

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
    List<CmsService.MemberView> members() { return cms.members(); }

    @GetMapping("/members/{id}")
    CmsService.MemberView member(@PathVariable UUID id) { return cms.member(id); }

    @GetMapping("/menus")
    List<CmsService.MenuView> menus() { return cms.menus(); }

    @PostMapping("/menus")
    CmsService.MenuView createMenu(@Valid @RequestBody MenuRequest request) {
        return cms.createMenu(request.name(), request.path(), request.parentId(), request.displayOrder(),
                request.targetType(), request.targetId());
    }

    @PutMapping("/menus/{id}")
    CmsService.MenuView updateMenu(
            @PathVariable long id, @Valid @RequestBody MenuRequest request) {
        return cms.updateMenu(id, request.name(), request.path(), request.parentId(),
                request.displayOrder(), request.targetType(), request.targetId());
    }

    @DeleteMapping("/menus/{id}")
    ResponseEntity<Void> deleteMenu(@PathVariable long id) {
        cms.deleteMenu(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contents")
    List<CmsService.ContentView> contents() { return cms.contents(); }

    @GetMapping("/contents/{id}")
    CmsService.ContentView content(@PathVariable long id) { return cms.content(id); }

    @PostMapping("/contents")
    CmsService.ContentView createContent(
            Authentication authentication, @Valid @RequestBody ArticleRequest request) {
        return cms.createContent(actor(authentication).actorId(), request.title(), request.body());
    }

    @PutMapping("/contents/{id}")
    CmsService.ContentView updateContent(
            @PathVariable long id, @Valid @RequestBody ArticleRequest request) {
        return cms.updateContent(id, request.title(), request.body());
    }

    @DeleteMapping("/contents/{id}")
    ResponseEntity<Void> deleteContent(@PathVariable long id) {
        cms.deleteContent(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/boards")
    List<CmsService.BoardView> boards() { return cms.boards(); }

    @GetMapping("/boards/{id}")
    CmsService.BoardView board(@PathVariable long id) { return cms.board(id); }

    @PostMapping("/boards")
    CmsService.BoardView createBoard(@Valid @RequestBody BoardRequest request) {
        return cms.createBoard(request.name(), request.description());
    }

    @PutMapping("/boards/{id}")
    CmsService.BoardView updateBoard(
            @PathVariable long id, @Valid @RequestBody BoardRequest request) {
        return cms.updateBoard(id, request.name(), request.description());
    }

    @DeleteMapping("/boards/{id}")
    ResponseEntity<Void> deleteBoard(@PathVariable long id) {
        cms.deleteBoard(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/boards/{boardId}/posts")
    List<CmsService.PostView> posts(@PathVariable long boardId) { return cms.posts(boardId); }

    @PostMapping("/boards/{boardId}/posts")
    CmsService.PostView createPost(
            Authentication authentication,
            @PathVariable long boardId,
            @Valid @RequestBody ArticleRequest request) {
        return cms.createPost(actor(authentication).actorId(), boardId, request.title(), request.body());
    }

    @GetMapping("/posts/{id}")
    CmsService.PostView post(@PathVariable long id) { return cms.post(id); }

    @PutMapping("/posts/{id}")
    CmsService.PostView updatePost(
            @PathVariable long id, @Valid @RequestBody ArticleRequest request) {
        return cms.updatePost(id, request.title(), request.body());
    }

    @DeleteMapping("/posts/{id}")
    ResponseEntity<Void> deletePost(@PathVariable long id) {
        cms.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/templates")
    List<CmsService.TemplateView> templates() { return cms.templates(); }

    @PutMapping("/templates/{key}")
    CmsService.TemplateView saveTemplate(
            @PathVariable String key, @Valid @RequestBody TemplateRequest request) {
        return cms.saveTemplate(key, request.layout(), request.primaryColor(), request.siteName(),
                request.headerText(), request.footerText(), request.heroImageUrl(),
                request.heroTitle(), request.heroSubtitle(), request.heroButtonLabel(),
                request.heroButtonUrl());
    }

    private AuthenticatedActor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Authentication is required.");
        }
        try {
            return authService.loadActor(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException ex) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Authentication is required.", ex);
        }
    }

    public record MenuRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 180) String path,
            Long parentId,
            @Min(0) int displayOrder,
            @NotBlank @Pattern(regexp = "NONE|CONTENT|BOARD") String targetType,
            Long targetId) {}

    public record ArticleRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body) {}

    public record BoardRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 300) String description) {}

    public record TemplateRequest(
            @NotBlank @Size(max = 40) String layout,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
            @NotBlank @Size(max = 100) String siteName,
            @Size(max = 200) String headerText,
            @Size(max = 200) String footerText,
            @NotBlank @Size(max = 500) String heroImageUrl,
            @NotBlank @Size(max = 160) String heroTitle,
            @Size(max = 300) String heroSubtitle,
            @Size(max = 60) String heroButtonLabel,
            @Size(max = 180) String heroButtonUrl) {}
}
