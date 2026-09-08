package org.urizo.axmodulestudio.backend.cms.controller;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentImageBytes;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PostView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PublicSiteView;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;
import org.urizo.axmodulestudio.backend.cms.service.CmsSiteSettingsService;

@RestController
@Profile("local-full")
@RequestMapping("/api/site")
public class CmsSiteController {

    private final CmsService cms;
    private final CmsSiteSettingsService siteSettings;

    public CmsSiteController(CmsService cms, CmsSiteSettingsService siteSettings) {
        this.cms = cms;
        this.siteSettings = siteSettings;
    }

    @GetMapping("/menus")
    List<MenuView> menus() {
        return cms.menus();
    }

    @GetMapping("/contents")
    List<ContentView> contents() {
        return cms.contents();
    }

    @GetMapping("/contents/{id}")
    ContentView content(@PathVariable long id) {
        return cms.content(id);
    }

    /**
     * 컨텐츠 본문의 이미지. 방문자가 봐야 하므로 {@code /api/site} 아래에 둔다.
     *
     * <p>바이트를 그대로 내보낸다. 캐싱을 붙이지 않으면 페이지를 열 때마다 DB에서 다시 꺼낸다.
     * 이미지는 한 번 올리면 바뀌지 않으므로 오래 캐시해도 안전하다.
     */
    @GetMapping("/images/{id}")
    ResponseEntity<byte[]> image(@PathVariable long id) {
        ContentImageBytes image = cms.contentImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .eTag("\"" + id + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(image.bytes());
    }

    @GetMapping("/boards")
    List<BoardView> boards() {
        return cms.boards();
    }

    @GetMapping("/boards/{boardId}/posts")
    List<PostView> posts(@PathVariable long boardId) {
        return cms.posts(boardId);
    }

    @GetMapping("/posts/{id}")
    PostView post(@PathVariable long id) {
        return cms.post(id);
    }

    @GetMapping("/context")
    PublicSiteView context(@RequestParam(defaultValue = "/") String path) {
        return siteSettings.resolveSite(path);
    }
}
