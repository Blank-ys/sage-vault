package com.sagevault.kb.feedback.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.sagevault.kb.feedback.domain.AdminFeedbackDetail;
import com.sagevault.kb.feedback.domain.AdminFeedbackPage;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.feedback.domain.ResolveFeedbackRequest;
import com.sagevault.kb.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈管理端接口。
 *
 * <p>与用户提交入口分开：这里的每个端点都要求管理权限，
 * 且只能触达用户已授权共享的问答内容。
 */
@RestController
@RequestMapping("/admin/feedback")
public class AdminFeedbackController {

    private static final String MANAGE_PERMISSION = "sage:feedback:manage";

    private final FeedbackService feedbacks;

    public AdminFeedbackController(FeedbackService feedbacks) {
        this.feedbacks = feedbacks;
    }

    @RequiresPermissions(MANAGE_PERMISSION)
    @GetMapping
    public R<AdminFeedbackPage> list(
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(feedbacks.listForAdmin(AdminFeedbackQuery.of(status, pageNum, pageSize)));
    }

    @RequiresPermissions(MANAGE_PERMISSION)
    @GetMapping("/{id}")
    public R<AdminFeedbackDetail> detail(@PathVariable long id) {
        return R.ok(feedbacks.findDetailForAdmin(SecurityUtils.getUserId(), id));
    }

    @RequiresPermissions(MANAGE_PERMISSION)
    @PutMapping("/{id}/status")
    public R<AdminFeedbackDetail> resolve(
            @PathVariable long id, @Valid @RequestBody ResolveFeedbackRequest request) {
        return R.ok(feedbacks.resolve(SecurityUtils.getUserId(), id, request));
    }
}
