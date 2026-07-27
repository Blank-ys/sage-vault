package com.sagevault.kb.knowledgebase.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresLogin;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) { this.service = service; }

    @RequiresPermissions("sage:knowledge-base:manage")
    @PostMapping
    public R<KnowledgeBaseResponse> create(@RequestBody CreateKnowledgeBaseRequest request) { return R.ok(service.create(request)); }

    @RequiresPermissions("sage:knowledge-base:manage")
    @GetMapping
    public R<List<KnowledgeBaseResponse>> list() { return R.ok(service.listAll()); }

    @RequiresPermissions("sage:knowledge-base:manage")
    @GetMapping("/{id}")
    public R<KnowledgeBaseResponse> get(@PathVariable long id) { return R.ok(service.get(id)); }

    @RequiresPermissions("sage:knowledge-base:manage")
    @PutMapping("/{id}")
    public R<KnowledgeBaseResponse> update(@PathVariable long id, @RequestBody UpdateKnowledgeBaseRequest request) { return R.ok(service.update(id, request)); }

    @RequiresLogin
    @GetMapping("/available")
    public R<List<KnowledgeBaseResponse>> available() { return R.ok(service.listAvailable()); }
}
