package com.sagevault.kb.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuoyiFeedbackAuditTest {
    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    @Test
    void recordsResolvedWithoutBody() throws Exception {
        SecurityContextHolder.setUserName("feedback-admin");
        RemoteLogService remote = mock(RemoteLogService.class);
        RuoyiFeedbackAudit audit = new RuoyiFeedbackAudit(remote);

        audit.recordResolved(99L, "RESOLVED");

        ArgumentCaptor<SysOperLog> event = ArgumentCaptor.forClass(SysOperLog.class);
        verify(remote).saveLog(event.capture(), eq(SecurityConstants.INNER));
        assertThat(event.getValue().getOperParam()).isEqualTo("feedbackId=99,status=RESOLVED");
        assertThat(event.getValue().getStatus()).isEqualTo(0);
        assertThat(event.getValue().getErrorMsg()).isNull();
    }

    @Test
    void recordsResolveFailureWithStatusAndReason() throws Exception {
        SecurityContextHolder.setUserName("feedback-admin");
        RemoteLogService remote = mock(RemoteLogService.class);
        RuoyiFeedbackAudit audit = new RuoyiFeedbackAudit(remote);

        audit.recordResolveFailed(99L, "反馈不存在");

        ArgumentCaptor<SysOperLog> event = ArgumentCaptor.forClass(SysOperLog.class);
        verify(remote).saveLog(event.capture(), eq(SecurityConstants.INNER));
        assertThat(event.getValue().getOperParam()).isEqualTo("feedbackId=99");
        assertThat(event.getValue().getStatus()).isEqualTo(1);
        assertThat(event.getValue().getErrorMsg()).isEqualTo("反馈不存在");
    }
}
