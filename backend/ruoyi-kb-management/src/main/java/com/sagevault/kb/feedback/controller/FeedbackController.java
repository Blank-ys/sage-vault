package com.sagevault.kb.feedback.controller;

import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresLogin;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.sagevault.kb.feedback.domain.FeedbackResponse;
import com.sagevault.kb.feedback.domain.SubmitFeedbackRequest;
import com.sagevault.kb.feedback.service.FeedbackService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/qa")
public class FeedbackController {
    private final FeedbackService feedbacks;

    public FeedbackController(FeedbackService feedbacks) {
        this.feedbacks = feedbacks;
    }

    /** 用户对自己的某条问答提交反馈；归属由服务端按登录身份判定，不接受前端传入的用户标识。 */
    @PostMapping("/{qaId}/feedback")
    @RequiresLogin
    public R<FeedbackResponse> submit(@PathVariable long qaId, @RequestBody SubmitFeedbackRequest request) {
        return R.ok(feedbacks.submit(SecurityUtils.getUserId(), qaId, request));
    }
}
