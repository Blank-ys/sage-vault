package com.sagevault.kb.conversation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.utils.SpringUtils;
import com.ruoyi.common.security.aspect.PreAuthorizeAspect;
import com.ruoyi.common.security.handler.GlobalExceptionHandler;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConversationAuthorizationTest {
    private MockMvc mockMvc;
    private ConversationService conversations;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tokenService", mock(TokenService.class));
        new SpringUtils().postProcessBeanFactory(beanFactory);
        conversations = mock(ConversationService.class);
        when(conversations.create(eq(7L), any())).thenReturn(new ConversationResponse(1L, 7L, 10L));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new ConversationController(conversations));
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
    void anonymousUserCannotCreateConversation() throws Exception {
        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void anyLoggedInUserCanCreateConversation() throws Exception {
        authenticate(7L, Set.of());
        mockMvc.perform(post("/conversations")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeBaseId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    private void authenticate(long userId, Set<String> permissions) {
        LoginUser user = new LoginUser();
        user.setToken("test-token");
        user.setUserid(userId);
        user.setPermissions(permissions);
        user.setRoles(Set.of());
        user.setExpireTime(Long.MAX_VALUE);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, user);
        SecurityContextHolder.setUserId(String.valueOf(userId));
    }
}
