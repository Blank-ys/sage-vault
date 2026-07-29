package com.sagevault.kb.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class BusinessExceptionHandlerTest {
    private final BusinessExceptionHandler handler = new BusinessExceptionHandler();

    @Test
    void handlesBusinessExceptionWithCodeAndMessage() {
        BusinessException exception = new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");

        R<Void> response = handler.handle(exception);

        assertThat(response.getCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND.code());
        assertThat(response.getMsg()).isEqualTo("文档不存在");
    }

    @Test
    void handlesMaxUploadSizeExceededException() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(52428800L);

        R<Void> response = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.getCode()).isEqualTo(ErrorCode.DOCUMENT_FILE_TOO_LARGE.code());
        assertThat(response.getMsg()).isEqualTo("上传文件不得超过50MB");
    }
}
