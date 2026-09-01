package org.urizo.axmodulestudio.backend.cms.controller;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
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
