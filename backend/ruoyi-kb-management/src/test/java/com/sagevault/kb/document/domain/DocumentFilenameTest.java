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
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> DocumentFilename.of("archive.exe"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持上传 TXT、PDF、DOCX、MD 文件");
    }

    @Test
    void acceptsPdfDocxAndMdExtensions() {
        assertThat(DocumentFilename.of("spec.pdf").extension()).isEqualTo("pdf");
        assertThat(DocumentFilename.of("guide.DOCX").extension()).isEqualTo("docx");
        assertThat(DocumentFilename.of("notes.Md").extension()).isEqualTo("md");
    }

    @Test
    void resolvesContentTypeByExtension() {
        assertThat(DocumentFilename.of("notes.txt").contentType()).isEqualTo("text/plain");
        assertThat(DocumentFilename.of("spec.pdf").contentType()).isEqualTo("application/pdf");
        assertThat(DocumentFilename.of("guide.docx").contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(DocumentFilename.of("readme.md").contentType()).isEqualTo("text/markdown");
    }
}
