package com.sagevault.kb.knowledgebase.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.utils.SpringUtils;
import com.ruoyi.common.security.aspect.PreAuthorizeAspect;
import com.ruoyi.common.security.handler.GlobalExceptionHandler;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;
import com.sagevault.kb.knowledgebase.service.KnowledgeBaseService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeBaseAuthorizationTest {
    private static final String MANAGE_PERMISSION = "sage:knowledge-base:manage";

    private MockMvc mockMvc;
    private KnowledgeBaseService knowledgeBases;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tokenService", mock(TokenService.class));
        new SpringUtils().postProcessBeanFactory(beanFactory);
        knowledgeBases = mock(KnowledgeBaseService.class);
        when(knowledgeBases.listAll()).thenReturn(java.util.List.of());
        when(knowledgeBases.listAvailable()).thenReturn(java.util.List.of());
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new KnowledgeBaseController(knowledgeBases));
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
    void anonymousUserCannotListAvailableKnowledgeBases() throws Exception {
        mockMvc.perform(get("/knowledge-bases/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void generalUserCanListAvailableKnowledgeBasesButCannotOpenManagementApi() throws Exception {
        authenticate(Set.of(), Set.of());
        mockMvc.perform(get("/knowledge-bases/available").header("Authorization", "Bearer general-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/knowledge-bases").header("Authorization", "Bearer general-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void userWithManagePermissionButNoKnowledgeAdminRoleIsRejected() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION), Set.of());
        mockMvc.perform(get("/knowledge-bases").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void knowledgeAdministratorCanOpenManagementApi() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        mockMvc.perform(get("/knowledge-bases").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void businessExceptionUsesRegisteredCodeAndMessage() throws Exception {
        authenticate(Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(knowledgeBases.get(9L)).thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE,
                "knowledge base is unavailable"));

        mockMvc.perform(get("/knowledge-bases/9").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.KNOWLEDGE_BASE_NOT_AVAILABLE.code()))
                .andExpect(jsonPath("$.msg").value("knowledge base is unavailable"));
    }

    private void authenticate(Set<String> permissions, Set<String> roles) {
        LoginUser user = new LoginUser();
        user.setToken("test-token");
        user.setUserid(7L);
        user.setPermissions(permissions);
        user.setRoles(roles);
        user.setExpireTime(Long.MAX_VALUE);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, user);
    }
}
