package com.sagevault.kb.document.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.UploadDocumentRequest;
import com.sagevault.kb.document.service.DocumentService;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @RequiresPermissions("sage:document:manage")
    @PostMapping
    public R<DocumentResponse> upload(@RequestParam long knowledgeBaseId, @RequestPart MultipartFile file) {
        return R.ok(service.upload(new UploadDocumentRequest(knowledgeBaseId, file)));
    }

    @RequiresPermissions("sage:document:manage")
    @PostMapping("/batch")
    public R<List<DocumentResponse>> uploadBatch(@RequestParam long knowledgeBaseId,
            @RequestPart MultipartFile[] files) {
        return R.ok(service.uploadBatch(knowledgeBaseId, Arrays.asList(files)));
    }

    @RequiresPermissions("sage:document:manage")
    @GetMapping
    public R<List<DocumentResponse>> list(@RequestParam long knowledgeBaseId) {
        return R.ok(service.listByKnowledgeBase(knowledgeBaseId));
    }

    @RequiresPermissions("sage:document:manage")
    @PostMapping("/{id}/retry")
    public R<DocumentResponse> retry(@PathVariable long id) {
        return R.ok(service.retry(id));
    }

    @RequiresPermissions("sage:document:manage")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        service.delete(id);
        return R.ok();
    }
}
