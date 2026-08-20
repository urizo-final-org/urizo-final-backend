package org.urizo.axmodulestudio.backend.cms;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local-full")
@RequestMapping("/api/site")
public class CmsSiteController {

    private final CmsService cms;

    public CmsSiteController(CmsService cms) {
        this.cms = cms;
    }

    @GetMapping("/menus")
    List<CmsService.MenuView> menus() { return cms.menus(); }

    @GetMapping("/contents")
    List<CmsService.ContentView> contents() { return cms.contents(); }

    @GetMapping("/contents/{id}")
    CmsService.ContentView content(@PathVariable long id) { return cms.content(id); }

    @GetMapping("/boards")
    List<CmsService.BoardView> boards() { return cms.boards(); }

    @GetMapping("/boards/{boardId}/posts")
    List<CmsService.PostView> posts(@PathVariable long boardId) { return cms.posts(boardId); }

    @GetMapping("/posts/{id}")
    CmsService.PostView post(@PathVariable long id) { return cms.post(id); }

    @GetMapping("/template")
    CmsService.TemplateView template() { return cms.activeTemplate(); }
}
