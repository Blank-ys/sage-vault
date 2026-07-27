package com.sagevault.kb.platform.error;

import com.ruoyi.common.core.domain.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BusinessExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public R<Void> handle(BusinessException exception) {
        return R.fail(exception.errorCode().code(), exception.getMessage());
    }
}
