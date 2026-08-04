package com.sagevault.kb.platform.audit;

import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.RemoteLogService;
import com.ruoyi.system.api.domain.SysOperLog;
import com.sagevault.kb.feedback.service.port.FeedbackAudit;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class RuoyiFeedbackAudit implements FeedbackAudit {

    private static final int BUSINESS_TYPE_UPDATE = 2;

    private static final int BUSINESS_TYPE_QUERY = 5;

    private final RemoteLogService remoteLogService;

    public RuoyiFeedbackAudit(RemoteLogService remoteLogService) {
        this.remoteLogService = remoteLogService;
    }

    @Override
    public void recordViewed(long feedbackId, long qaId) {
        // 只记录标识，问答正文不进入操作日志
        save(BUSINESS_TYPE_QUERY, "VIEW", "feedbackId=" + feedbackId + ",qaId=" + qaId, 0, null);
    }

    @Override
    public void recordResolved(long feedbackId, String status) {
        save(BUSINESS_TYPE_UPDATE, "RESOLVE", "feedbackId=" + feedbackId + ",status=" + status, 0, null);
    }

    @Override
    public void recordResolveFailed(long feedbackId, String errorMessage) {
        // 失败原因仅含标识与状态，不写入反馈说明正文
        save(BUSINESS_TYPE_UPDATE, "RESOLVE", "feedbackId=" + feedbackId, 1, errorMessage);
    }

    private void save(int businessType, String method, String param, int status, String errorMessage) {
        SysOperLog event = new SysOperLog();
        event.setTitle("Sage Vault 反馈管理");
        event.setBusinessType(businessType);
        event.setOperName(SecurityUtils.getUsername());
        event.setMethod(method);
        event.setOperParam(param);
        event.setStatus(status);
        if (errorMessage != null) {
            event.setErrorMsg(truncate(errorMessage));
        }
        try {
            remoteLogService.saveLog(event, SecurityConstants.INNER);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE, "反馈审计暂不可用", exception);
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
