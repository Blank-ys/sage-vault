package com.sagevault.kb.feedback.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import com.sagevault.kb.platform.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FeedbackAuthorizationTest {
    private MockMvc mockMvc;
    private FeedbackService feedbacks;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tokenService", mock(TokenService.class));
        new SpringUtils().postProcessBeanFactory(beanFactory);
        feedbacks = mock(FeedbackService.class);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new FeedbackController(feedbacks));
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
    void anonymousUserCannotSubmitFeedback() throws Exception {
        mockMvc.perform(post("/qa/1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WRONG_ANSWER\",\"consentToShare\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verify(feedbacks, never()).submit(anyLong(), anyLong(), any());
    }

    @Test
    void feedbackIsAttributedToTheLoggedInUserNotToTheRequestBody() throws Exception {
        authenticate(7L);
        when(feedbacks.submit(eq(7L), eq(1L), any())).thenReturn(
                new FeedbackResponse(5L, 1L, FeedbackCategory.WRONG_ANSWER, "说明", LocalDateTime.now()));

        mockMvc.perform(post("/qa/1/feedback")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WRONG_ANSWER\",\"comment\":\"说明\","
                                + "\"consentToShare\":true,\"userId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(5));

        // 请求体里的 userId 不得影响归属，服务端只认登录身份
        verify(feedbacks).submit(eq(7L), eq(1L), any());
    }

    @Test
    void submittingFeedbackOnAnotherUsersQaRecordIsRefused() throws Exception {
        authenticate(7L);
        when(feedbacks.submit(eq(7L), eq(99L), any()))
                .thenThrow(new BusinessException(ErrorCode.FEEDBACK_FORBIDDEN, "无权对该问答提交反馈"));

        mockMvc.perform(post("/qa/99/feedback")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WRONG_ANSWER\",\"consentToShare\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.FEEDBACK_FORBIDDEN.code()));
    }

    @Test
    void missingConsentIsPassedThroughAsRefusalNotAsSilentSuccess() throws Exception {
        authenticate(7L);
        ArgumentCaptor<SubmitFeedbackRequest> captor = ArgumentCaptor.forClass(SubmitFeedbackRequest.class);
        when(feedbacks.submit(eq(7L), eq(1L), captor.capture()))
                .thenThrow(new BusinessException(ErrorCode.FEEDBACK_CONSENT_REQUIRED, "需要同意共享问答内容后才能提交反馈"));

        mockMvc.perform(post("/qa/1/feedback")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"WRONG_ANSWER\",\"comment\":\"内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.FEEDBACK_CONSENT_REQUIRED.code()));

        org.assertj.core.api.Assertions.assertThat(captor.getValue().consentToShare()).isNull();
    }

    @Test
    void adminNoteIsNeverExposedToTheSubmittingUser() throws Exception {
        authenticate(7L);
        when(feedbacks.submit(eq(7L), eq(1L), any())).thenReturn(
                new FeedbackResponse(5L, 1L, FeedbackCategory.OTHER, "说明", LocalDateTime.now()));

        mockMvc.perform(post("/qa/1/feedback")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"OTHER\",\"consentToShare\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adminNote").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());
    }

    private void authenticate(long userId) {
        LoginUser user = new LoginUser();
        user.setToken("test-token");
        user.setUserid(userId);
        user.setPermissions(Set.of());
        user.setRoles(Set.of());
        user.setExpireTime(Long.MAX_VALUE);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, user);
        SecurityContextHolder.setUserId(String.valueOf(userId));
    }
}
