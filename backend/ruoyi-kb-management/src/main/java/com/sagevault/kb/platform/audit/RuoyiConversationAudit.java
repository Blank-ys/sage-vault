package com.sagevault.kb.platform.audit;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import com.sagevault.kb.conversation.service.port.ConversationAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class RuoyiConversationAudit implements ConversationAudit {
    private static final int BUSINESS_TYPE_DELETE = 3;

    private final RemoteLogService remoteLogService;

    public RuoyiConversationAudit(RemoteLogService remoteLogService) {
        this.remoteLogService = remoteLogService;
    }

    @Override
    public void recordDeleted(long conversationId, int removedRecordCount) {
        SysOperLog event = new SysOperLog();
        event.setTitle("Sage Vault 会话管理");
        event.setBusinessType(BUSINESS_TYPE_DELETE);
        event.setOperName(SecurityUtils.getUsername());
        event.setMethod("DELETE");
        event.setOperParam("conversationId=" + conversationId + ",removedRecordCount=" + removedRecordCount);
        event.setStatus(0);
        try {
            remoteLogService.saveLog(event, SecurityConstants.INNER);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE, "会话审计暂不可用", exception);
        }
    }
}
