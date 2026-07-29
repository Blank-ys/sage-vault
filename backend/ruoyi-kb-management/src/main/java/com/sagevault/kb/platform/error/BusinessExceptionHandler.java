package com.sagevault.kb.platform.error;

import com.ruoyi.common.core.domain.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class BusinessExceptionHandler {
    private static final String MAX_FILE_SIZE_MESSAGE = "上传文件不得超过50MB";

    @ExceptionHandler(BusinessException.class)
    public R<Void> handle(BusinessException exception) {
        return R.fail(exception.errorCode().code(), exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return R.fail(ErrorCode.DOCUMENT_FILE_TOO_LARGE.code(), MAX_FILE_SIZE_MESSAGE);
    }
}
