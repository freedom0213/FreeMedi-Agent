package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KnowledgeMarkdownParserTest {

    private final KnowledgeMarkdownParser parser = new KnowledgeMarkdownParser();

    private static final String VALID = """
            ---
            doc_no: TEST-001
            title: 测试资料
            type: APPOINTMENT_RULE
            source: 测试用合成资料
            ---

            ## 一节

            正文内容。
            """;

    @Test
    void readsFrontMatterAndBody() {
        KnowledgeMarkdownParser.ParsedDocument parsed = parser.parse(VALID, "test.md");

        assertThat(parsed.docNo()).isEqualTo("TEST-001");
        assertThat(parsed.title()).isEqualTo("测试资料");
        assertThat(parsed.docType()).isEqualTo(KnowledgeDocType.APPOINTMENT_RULE);
        assertThat(parsed.source()).isEqualTo("测试用合成资料");
        assertThat(parsed.departmentId()).isNull();
        assertThat(parsed.doctorId()).isNull();
        assertThat(parsed.body()).isEqualTo("## 一节\n\n正文内容。");
        assertThat(parsed.contentHash()).hasSize(64);
    }

    @Test
    void anUnknownKeyIsRejectedRatherThanIgnored() {
        // A misspelled doctor_id would otherwise publish a doctor profile that matches no doctor.
        String text = VALID.replace("source: 测试用合成资料", "source: 测试用合成资料\ndoctorId: 3001");

        assertThatThrownBy(() -> parser.parse(text, "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doctorId");
    }

    @Test
    void missingRequiredKeysAreRejected() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("title: 测试资料\n", ""), "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void aDoctorProfileMustNameADoctor() {
        String text = VALID.replace("type: APPOINTMENT_RULE", "type: DOCTOR_PROFILE");

        assertThatThrownBy(() -> parser.parse(text, "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doctor_id");
    }

    @Test
    void aDepartmentIntroMustNameADepartment() {
        String text = VALID.replace("type: APPOINTMENT_RULE", "type: DEPARTMENT_INTRO");

        assertThatThrownBy(() -> parser.parse(text, "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("department_id");
    }

    @Test
    void identifierMustBeAPositiveNumber() {
        String text = VALID.replace("source: 测试用合成资料", "source: 测试用合成资料\ndepartment_id: abc")
                .replace("type: APPOINTMENT_RULE", "type: DEPARTMENT_INTRO");

        assertThatThrownBy(() -> parser.parse(text, "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("department_id");
    }

    @Test
    void unknownDocTypeIsRejected() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("type: APPOINTMENT_RULE", "type: SOMETHING_ELSE"),
                "test.md")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资料类型");
    }

    @Test
    void aFileWithoutFrontMatterIsRejected() {
        assertThatThrownBy(() -> parser.parse("# 标题\n\n正文", "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开头");
    }

    @Test
    void unterminatedFrontMatterIsRejected() {
        assertThatThrownBy(() -> parser.parse("---\ndoc_no: X\ntitle: T\ntype: PREPARATION\nsource: S",
                "test.md")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结束");
    }

    @Test
    void aLineWithoutAColonInsideFrontMatterIsRejected() {
        assertThatThrownBy(() -> parser.parse("---\ndoc_no: X\n这不是键值对\n---\n正文", "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key: value");
    }

    @Test
    void anEmptyBodyIsRejected() {
        assertThatThrownBy(() -> parser.parse(VALID.substring(0, VALID.indexOf("## 一节")), "test.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");
    }

    @Test
    void theHashCoversTheMetadataAsWellAsTheBody() {
        KnowledgeMarkdownParser.ParsedDocument original = parser.parse(VALID, "test.md");
        KnowledgeMarkdownParser.ParsedDocument renamed =
                parser.parse(VALID.replace("title: 测试资料", "title: 改名后的资料"), "test.md");
        KnowledgeMarkdownParser.ParsedDocument identical = parser.parse(VALID, "other-name.md");

        assertThat(renamed.contentHash()).isNotEqualTo(original.contentHash());
        // The file name is not part of the content, so the hash does not depend on where it lives.
        assertThat(identical.contentHash()).isEqualTo(original.contentHash());
    }
}
