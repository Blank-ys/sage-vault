package com.sagevault.kb.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.platform.error.BusinessException;
import org.junit.jupiter.api.Test;

class DocumentFilenameTest {
    @Test
    void normalizesNameToLowerCase() {
        DocumentFilename filename = DocumentFilename.of("Report.TXT");

        assertThat(filename.value()).isEqualTo("Report.TXT");
        assertThat(filename.normalizedValue()).isEqualTo("report.txt");
        assertThat(filename.extension()).isEqualTo("txt");
    }

    @Test
    void trimsWhitespace() {
        DocumentFilename filename = DocumentFilename.of("  Report.TXT  ");

        assertThat(filename.normalizedValue()).isEqualTo("report.txt");
    }

    @Test
    void rejectsEmptyName() {
        assertThatThrownBy(() -> DocumentFilename.of("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文档名称不能为空");
    }

    @Test
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> DocumentFilename.of("README"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("扩展名");
    }

    @Test
    void rejectsNonTxtFiles() {
        assertThatThrownBy(() -> DocumentFilename.of("report.pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持上传 TXT 文件");
    }
}
