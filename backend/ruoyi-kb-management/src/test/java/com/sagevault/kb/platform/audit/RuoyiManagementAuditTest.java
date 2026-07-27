package com.sagevault.kb.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import com.sagevault.kb.knowledgebase.service.port.ManagementAudit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuoyiManagementAuditTest {
    @AfterEach
    void tearDown() {
        SecurityContextHolder.remove();
    }

    @Test
    void emitsOnlyWhitelistedKnowledgeBaseFields() throws Exception {
        SecurityContextHolder.setUserName("knowledge-admin");
        RemoteLogService remote = mock(RemoteLogService.class);
        RuoyiManagementAudit audit = new RuoyiManagementAudit(remote);

        audit.record(ManagementAudit.Operation.UPDATE, 42L);

        ArgumentCaptor<SysOperLog> event = ArgumentCaptor.forClass(SysOperLog.class);
        verify(remote).saveLog(event.capture(), eq(SecurityConstants.INNER));
        assertThat(event.getValue().getOperParam()).isEqualTo("knowledgeBaseId=42");
        assertThat(event.getValue().getJsonResult()).isNull();
        assertThat(event.getValue().getErrorMsg()).isNull();
    }
}
