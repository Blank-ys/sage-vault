package com.sagevault.kb.feedback.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.sagevault.kb.feedback.domain.AdminFeedbackDetail;
import com.sagevault.kb.feedback.domain.AdminFeedbackPage;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.service.FeedbackService;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.BusinessExceptionHandler;
import com.sagevault.kb.platform.error.ErrorCode;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

class AdminFeedbackAuthorizationTest {

    private static final String MANAGE_PERMISSION = "sage:feedback:manage";

    private MockMvc mockMvc;
    private FeedbackService feedbacks;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tokenService", mock(TokenService.class));
        new SpringUtils().postProcessBeanFactory(beanFactory);
        feedbacks = mock(FeedbackService.class);
        AspectJProxyFactory proxyFactory =
                new AspectJProxyFactory(new AdminFeedbackController(feedbacks));
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
    void anonymousUserCannotReadTheFeedbackQueue() throws Exception {
        mockMvc.perform(get("/admin/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verify(feedbacks, never()).listForAdmin(any());
    }

    @Test
    void anOrdinaryUserWithoutTheManagePermissionCannotReadFeedbackDetail() throws Exception {
        authenticate(7L, Set.of(), Set.of());

        mockMvc.perform(get("/admin/feedback/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // 权限不足时服务层完全不被触达，问答正文没有任何被读出的机会
        verify(feedbacks, never()).findDetailForAdmin(anyLong(), anyLong());
    }

    @Test
    void aUserWithManagePermissionButNoKnowledgeAdminRoleIsRejected() throws Exception {
        authenticate(7L, Set.of(MANAGE_PERMISSION), Set.of());

        mockMvc.perform(get("/admin/feedback/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // 角色不足时服务层完全不被触达，问答正文没有任何被读出的机会
        verify(feedbacks, never()).findDetailForAdmin(anyLong(), anyLong());
    }

    @Test
    void anOrdinaryUserCannotResolveFeedback() throws Exception {
        authenticate(7L, Set.of(), Set.of());

        mockMvc.perform(put("/admin/feedback/1/status")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verify(feedbacks, never()).resolve(anyLong(), anyLong(), any());
    }

    @Test
    void anAdministratorSeesTheSharedQuestionAndAnswer() throws Exception {
        authenticate(9L, Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(feedbacks.findDetailForAdmin(9L, 1L)).thenReturn(detail("完整回答", QaRecordStatus.COMPLETED));

        mockMvc.perform(get("/admin/feedback/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.question").value("问题正文"))
                .andExpect(jsonPath("$.data.answer").value("完整回答"))
                .andExpect(jsonPath("$.data.requestId").value("req-1"));
    }

    @Test
    void aPartialAnswerIsShownAsStoredSoTheAdminCanDiagnoseIt() throws Exception {
        authenticate(9L, Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(feedbacks.findDetailForAdmin(9L, 1L)).thenReturn(detail("残缺回", QaRecordStatus.STOPPED));

        mockMvc.perform(get("/admin/feedback/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("残缺回"))
                .andExpect(jsonPath("$.data.answerStatus").value("STOPPED"));
    }

    @Test
    void theResolvingAdminIsTakenFromTheLoginNotFromTheRequest() throws Exception {
        authenticate(9L, Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(feedbacks.resolve(anyLong(), anyLong(), any()))
                .thenReturn(detail("完整回答", QaRecordStatus.COMPLETED));
        ArgumentCaptor<Long> adminId = ArgumentCaptor.forClass(Long.class);

        mockMvc.perform(put("/admin/feedback/1/status")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"adminNote\":\"已修正文档\",\"adminUserId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(feedbacks).resolve(adminId.capture(), anyLong(), any());
        org.assertj.core.api.Assertions.assertThat(adminId.getValue()).isEqualTo(9L);
    }

    @Test
    void aMissingFeedbackIsReportedAsNotFound() throws Exception {
        authenticate(9L, Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(feedbacks.findDetailForAdmin(9L, 404L))
                .thenThrow(new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND, "反馈不存在"));

        mockMvc.perform(get("/admin/feedback/404").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.FEEDBACK_NOT_FOUND.code()));
    }

    @Test
    void theQueueCanBeFilteredByStatus() throws Exception {
        authenticate(9L, Set.of(MANAGE_PERMISSION), Set.of("knowledge_admin"));
        when(feedbacks.listForAdmin(any()))
                .thenReturn(new AdminFeedbackPage(List.of(), 0, 1, 20));
        ArgumentCaptor<AdminFeedbackQuery> query = ArgumentCaptor.forClass(AdminFeedbackQuery.class);

        mockMvc.perform(get("/admin/feedback")
                        .param("status", "PENDING")
                        .param("pageNum", "2")
                        .param("pageSize", "5")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(feedbacks).listForAdmin(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().status())
                .isEqualTo(FeedbackStatus.PENDING);
        org.assertj.core.api.Assertions.assertThat(query.getValue().offset()).isEqualTo(5);
    }

    private static AdminFeedbackDetail detail(String answer, QaRecordStatus answerStatus) {
        return new AdminFeedbackDetail(
                1L,
                2L,
                FeedbackCategory.WRONG_ANSWER,
                "答案不对",
                FeedbackStatus.PENDING,
                "",
                LocalDateTime.now(),
                LocalDateTime.now(),
                "req-1",
                "问题正文",
                answer,
                answerStatus,
                List.of(),
                Map.of());
    }

    private void authenticate(long userId, Set<String> permissions, Set<String> roles) {
        LoginUser user = new LoginUser();
        user.setToken("test-token");
        user.setUserid(userId);
        user.setPermissions(permissions);
        user.setRoles(roles);
        user.setExpireTime(Long.MAX_VALUE);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, user);
        SecurityContextHolder.setUserId(String.valueOf(userId));
        SecurityContextHolder.setPermission(String.join(",", permissions));
    }
}
