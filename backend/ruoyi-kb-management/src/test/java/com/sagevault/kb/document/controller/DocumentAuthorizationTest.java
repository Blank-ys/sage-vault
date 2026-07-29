package com.sagevault.kb.document.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.utils.SpringUtils;
import com.ruoyi.common.security.aspect.PreAuthorizeAspect;
import com.ruoyi.common.security.handler.GlobalExceptionHandler;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;
import com.sagevault.kb.document.domain.DocumentResponse;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.service.DocumentService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import com.sagevault.kb.platform.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentAuthorizationTest {
    private static final String MANAGE_PERMISSION = "sage:document:manage";

    private MockMvc mockMvc;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tokenService", mock(TokenService.class));
        new SpringUtils().postProcessBeanFactory(beanFactory);
        service = mock(DocumentService.class);
        when(service.listByKnowledgeBase(7L)).thenReturn(List.of());
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new DocumentController(service));
        proxyFactory.addAspect(new PreAuthorizeAspect());
        Object controller = proxyFactory.getProxy();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BusinessExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    @Test
    void anonymousUserCannotListDocuments() throws Exception {
        mockMvc.perform(get("/documents").param("knowledgeBaseId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void generalUserCannotManageDocuments() throws Exception {
        authenticate(Set.of());
        mockMvc.perform(get("/documents").param("knowledgeBaseId", "7").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void knowledgeAdministratorCanUploadAndListDocuments() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION));
        MockMultipartFile file = new MockMultipartFile("file", "report.txt", "text/plain", "content".getBytes());
        when(service.upload(any())).thenReturn(new DocumentResponse(1L, 7L, "report.txt", "report.txt",
                DocumentStatus.PROCESSING, 7L, "", LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(multipart("/documents").file(file).param("knowledgeBaseId", "7")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        mockMvc.perform(get("/documents").param("knowledgeBaseId", "7").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void businessExceptionUsesRegisteredCodeAndMessage() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION));
        when(service.upload(any())).thenThrow(new BusinessException(ErrorCode.DOCUMENT_FILENAME_CONFLICT,
                "该知识库下已存在同名文档"));
        MockMultipartFile file = new MockMultipartFile("file", "report.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/documents").file(file).param("knowledgeBaseId", "7")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_FILENAME_CONFLICT.code()))
                .andExpect(jsonPath("$.msg").value("该知识库下已存在同名文档"));
    }

    @Test
    void generalUserCannotUploadBatch() throws Exception {
        authenticate(Set.of());
        MockMultipartFile file = new MockMultipartFile("files", "report.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/documents/batch").file(file).param("knowledgeBaseId", "7")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void knowledgeAdministratorCanUploadBatch() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION));
        MockMultipartFile firstFile = new MockMultipartFile("files", "alpha.txt", "text/plain", "a".getBytes());
        MockMultipartFile secondFile = new MockMultipartFile("files", "beta.pdf", "application/pdf", "b".getBytes());
        when(service.uploadBatch(anyLong(), any())).thenReturn(List.of(
                new DocumentResponse(1L, 7L, "alpha.txt", "alpha.txt", DocumentStatus.PROCESSING, 1L, "",
                        LocalDateTime.now(), LocalDateTime.now()),
                new DocumentResponse(2L, 7L, "beta.pdf", "beta.pdf", DocumentStatus.PROCESSING, 1L, "",
                        LocalDateTime.now(), LocalDateTime.now())));

        mockMvc.perform(multipart("/documents/batch").file(firstFile).file(secondFile).param("knowledgeBaseId", "7")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].filename").value("alpha.txt"))
                .andExpect(jsonPath("$.data[1].filename").value("beta.pdf"));
    }

    @Test
    void batchConflictReturnsRegisteredErrorCodeAndAllConflicts() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION));
        when(service.uploadBatch(anyLong(), any())).thenThrow(new BusinessException(ErrorCode.DOCUMENT_FILENAME_CONFLICT,
                "以下文件名在知识库内或本批中已存在：alpha.txt、beta.pdf"));
        MockMultipartFile file = new MockMultipartFile("files", "alpha.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/documents/batch").file(file).param("knowledgeBaseId", "7")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.DOCUMENT_FILENAME_CONFLICT.code()))
                .andExpect(jsonPath("$.msg").value("以下文件名在知识库内或本批中已存在：alpha.txt、beta.pdf"));
    }

    private void authenticate(Set<String> permissions) {
        LoginUser user = new LoginUser();
        user.setToken("test-token");
        user.setUserid(7L);
        user.setPermissions(permissions);
        user.setRoles(Set.of());
        user.setExpireTime(Long.MAX_VALUE);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, user);
    }
}
