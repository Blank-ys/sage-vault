package com.sagevault.kb.platform.audit;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import com.sagevault.kb.document.service.port.DocumentAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RuoyiDocumentAudit implements DocumentAudit {

    private static final int BUSINESS_TYPE_INSERT = 1;

    private static final int BUSINESS_TYPE_UPDATE = 2;

    private static final int BUSINESS_TYPE_DELETE = 3;

    private static final int STATUS_SUCCESS = 0;

    private static final int STATUS_FAILURE = 1;

    private static final Map<Operation, Integer> BUSINESS_TYPES = Map.of(
            Operation.UPLOAD, BUSINESS_TYPE_INSERT,
            Operation.DELETE, BUSINESS_TYPE_DELETE,
            Operation.RETRY, BUSINESS_TYPE_UPDATE,
            Operation.CLEANUP_RETRY, BUSINESS_TYPE_UPDATE);

    private final RemoteLogService remoteLogService;

    public RuoyiDocumentAudit(RemoteLogService remoteLogService) {
        this.remoteLogService = remoteLogService;
    }

    @Override
    public void record(Operation operation, long documentId, Long knowledgeBaseId) {
        // 只记录标识，文档名称与正文不进入操作日志
        save(operation, documentId, knowledgeBaseId, STATUS_SUCCESS, null);
    }

    @Override
    public void recordFailure(Operation operation, long documentId, Long knowledgeBaseId, String errorMessage) {
        // 只记录标识与失败原因，失败原因不得包含文档正文
        save(operation, documentId, knowledgeBaseId, STATUS_FAILURE, errorMessage);
    }

    private void save(Operation operation, long documentId, Long knowledgeBaseId, int status, String errorMessage) {
        SysOperLog event = new SysOperLog();
        event.setTitle("Sage Vault 企业文档管理");
        event.setBusinessType(BUSINESS_TYPES.get(operation));
        event.setOperName(SecurityUtils.getUsername());
        event.setMethod(operation.name());
        event.setOperParam("documentId=" + documentId + ",knowledgeBaseId=" + (knowledgeBaseId == null ? 0 : knowledgeBaseId));
        event.setStatus(status);
        if (errorMessage != null) {
            event.setErrorMsg(truncate(errorMessage));
        }
        try {
            remoteLogService.saveLog(event, SecurityConstants.INNER);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE, "文档审计暂不可用", exception);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        int limit = 2000;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
