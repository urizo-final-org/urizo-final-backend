package org.urizo.axmodulestudio.backend.cms.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.*;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.*;
import org.urizo.axmodulestudio.backend.cms.service.CmsCodeService;

/** Public reads keep disabled labels available for historical posts; all writes use the CMS admin boundary. */
@RestController
@Profile("local-full")
public class CmsCodeController {
    private final CmsCodeService codes;
    public CmsCodeController(CmsCodeService codes) { this.codes = codes; }

    @GetMapping({"/api/cms/code-groups", "/api/site/code-groups"})
    public List<CodeGroupView> groups() { return codes.groups(); }
    @GetMapping({"/api/cms/codes", "/api/site/codes"})
    public List<CodeView> codes() { return codes.codes(); }
    @PostMapping("/api/cms/code-groups")
    public CodeGroupView createGroup(@Valid @RequestBody CodeGroupRequest request) { return codes.createGroup(request); }
    @PutMapping("/api/cms/code-groups/{key}")
    public CodeGroupView updateGroup(@PathVariable String key, @Valid @RequestBody CodeGroupRequest request) { return codes.updateGroup(key, request); }
    @PostMapping("/api/cms/code-groups/{key}/codes")
    public CodeView createCode(@PathVariable String key, @Valid @RequestBody CodeRequest request) { return codes.createCode(key, request); }
    @PutMapping("/api/cms/codes/{id}")
    public CodeView updateCode(@PathVariable long id, @Valid @RequestBody CodeRequest request) { return codes.updateCode(id, request); }
}
