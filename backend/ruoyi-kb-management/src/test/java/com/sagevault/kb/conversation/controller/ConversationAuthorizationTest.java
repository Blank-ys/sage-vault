package com.sagevault.kb.conversation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.utils.SpringUtils;
import com.ruoyi.common.security.aspect.PreAuthorizeAspect;
import com.ruoyi.common.security.handler.GlobalExceptionHandler;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;
import com.sagevault.kb.conversation.domain.AnswerStateSnapshot;
import com.sagevault.kb.conversation.domain.ConversationResponse;
import com.sagevault.kb.conversation.service.ConversationService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import java.time.LocalDateTime;
import java.util.List;
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
        when(conversations.create(eq(7L), any()))
                .thenReturn(new ConversationResponse(1L, 7L, 10L, "", LocalDateTime.now(), LocalDateTime.now()));
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

    @Test
    void anonymousUserCannotListConversations() throws Exception {
        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void listOnlyReturnsConversationsOfTheCallingUser() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.list(7L)).thenReturn(List.of(
                new ConversationResponse(1L, 7L, 10L, "我的会话", LocalDateTime.now(), LocalDateTime.now())));

        mockMvc.perform(get("/conversations").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(7));

        verify(conversations).list(7L);
    }

    @Test
    void readingAnotherUsersConversationIsRefused() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.history(7L, 99L))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(get("/conversations/99/questions").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_FORBIDDEN.code()));
    }

    @Test
    void renamingAnotherUsersConversationIsRefused() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.rename(eq(7L), eq(99L), any()))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(put("/conversations/99/title")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_FORBIDDEN.code()));
    }

    @Test
    void deletingAnotherUsersConversationIsRefused() throws Exception {
        authenticate(7L, Set.of());
        doThrow(new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "无权访问该会话"))
                .when(conversations).delete(7L, 99L);

        mockMvc.perform(delete("/conversations/99").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_FORBIDDEN.code()));
    }

    @Test
    void anonymousUserCannotStopAnAnswer() throws Exception {
        mockMvc.perform(post("/conversations/1/answers/gen-1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(conversations, never()).stopAnswer(anyLong(), anyLong(), anyString());
    }

    @Test
    void stoppingAnotherUsersAnswerIsRefused() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.stopAnswer(7L, 99L, "gen-1"))
                .thenThrow(new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(post("/conversations/99/answers/gen-1/stop")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONVERSATION_FORBIDDEN.code()));
    }

    @Test
    void stoppingAnAlreadyFinishedAnswerSurfacesTheBusinessError() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.stopAnswer(7L, 1L, "gen-1"))
                .thenThrow(new BusinessException(ErrorCode.ANSWER_NOT_STOPPABLE, "该回答已结束，无法停止"));

        mockMvc.perform(post("/conversations/1/answers/gen-1/stop")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.ANSWER_NOT_STOPPABLE.code()));
    }

    @Test
    void stoppingOwnAnswerReturnsTheStoppedSnapshot() throws Exception {
        authenticate(7L, Set.of());
        when(conversations.stopAnswer(7L, 1L, "gen-1"))
                .thenReturn(new AnswerStateSnapshot("gen-1", true, QaRecordStatus.STOPPED, "已经生成的部分"));

        mockMvc.perform(post("/conversations/1/answers/gen-1/stop")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("STOPPED"))
                .andExpect(jsonPath("$.data.answer").value("已经生成的部分"));
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
