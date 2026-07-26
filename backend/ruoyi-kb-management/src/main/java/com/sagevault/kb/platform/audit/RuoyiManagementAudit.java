package com.sagevault.kb.platform.audit;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import com.sagevault.kb.knowledgebase.service.port.ManagementAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class RuoyiManagementAudit implements ManagementAudit {
    private final RemoteLogService remoteLogService;

    public RuoyiManagementAudit(RemoteLogService remoteLogService) {
        this.remoteLogService = remoteLogService;
    }

    @Override
    public void record(Operation operation, long knowledgeBaseId) {
        SysOperLog event = new SysOperLog();
        event.setTitle("Sage Vault 知识库管理");
        event.setBusinessType(operation == Operation.CREATE ? 1 : 2);
        event.setOperName(SecurityUtils.getUsername());
        event.setMethod(operation.name());
        event.setOperParam("knowledgeBaseId=" + knowledgeBaseId);
        event.setStatus(0);
        try {
            remoteLogService.saveLog(event, SecurityConstants.INNER);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE, "管理审计暂不可用", exception);
        }
    }
}
