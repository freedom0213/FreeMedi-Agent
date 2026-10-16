package com.mediflow.agent.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one material file: a small front-matter block followed by the Markdown body.
 *
 * <p>Unknown keys are rejected rather than ignored. A material file is written by hand, and silently
 * dropping {@code doctorId} when the key is spelled {@code doctor_id} would publish a doctor profile
 * that can never be matched to a real doctor.
 */
public final class KnowledgeMarkdownParser {

    private static final String FENCE = "---";
    private static final Set<String> REQUIRED = Set.of("doc_no", "title", "type", "source");
    private static final Set<String> OPTIONAL = Set.of("department_id", "doctor_id");

    public record ParsedDocument(String docNo, String title, KnowledgeDocType docType,
                                 Long departmentId, Long doctorId, String source, String body,
                                 String contentHash) {
    }

    public ParsedDocument parse(Path file) {
        final String raw;
        try {
            raw = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("无法读取资料文件 " + file + "：" + exception.getMessage(),
                    exception);
        }
        return parse(raw, file.toString());
    }

    ParsedDocument parse(String raw, String origin) {
        List<String> lines = List.of(raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        if (lines.isEmpty() || !FENCE.equals(lines.get(0).strip())) {
            throw new IllegalArgumentException(origin + " 必须以 " + FENCE + " 开头写入元信息");
        }
        int closing = -1;
        Map<String, String> front = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (FENCE.equals(line)) {
                closing = index;
                break;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException(origin + " 第 " + (index + 1)
                        + " 行的元信息不是 key: value 形式：" + line);
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (!REQUIRED.contains(key) && !OPTIONAL.contains(key)) {
                throw new IllegalArgumentException(origin + " 出现了无法识别的元信息键：" + key
                        + "，可用 " + REQUIRED + " 与 " + OPTIONAL);
            }
            front.put(key, value);
        }
        if (closing < 0) {
            throw new IllegalArgumentException(origin + " 的元信息没有用 " + FENCE + " 结束");
        }
        for (String key : REQUIRED) {
            if (front.getOrDefault(key, "").isBlank()) {
                throw new IllegalArgumentException(origin + " 缺少必填元信息：" + key);
            }
        }

        String body = String.join("\n", lines.subList(closing + 1, lines.size())).strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException(origin + " 的正文为空");
        }

        KnowledgeDocType type = KnowledgeDocType.from(front.get("type"));
        Long departmentId = optionalId(front, "department_id", origin);
        Long doctorId = optionalId(front, "doctor_id", origin);
        if (type == KnowledgeDocType.DOCTOR_PROFILE && doctorId == null) {
            throw new IllegalArgumentException(origin + " 是医生资料，必须给出 doctor_id");
        }
        if (type == KnowledgeDocType.DEPARTMENT_INTRO && departmentId == null) {
            throw new IllegalArgumentException(origin + " 是科室资料，必须给出 department_id");
        }

        String docNo = front.get("doc_no");
        String title = front.get("title");
        String source = front.get("source");
        String hash = sha256(String.join("\n", List.of(docNo, title, type.name(),
                String.valueOf(departmentId), String.valueOf(doctorId), source, body)));
        return new ParsedDocument(docNo, title, type, departmentId, doctorId, source, body, hash);
    }

    private static Long optionalId(Map<String, String> front, String key, String origin) {
        String value = front.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException("not positive");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(origin + " 的 " + key + " 必须是正整数：" + value);
        }
    }

    /**
     * Hash of everything that shapes the indexed output, not only the body, so that a renamed title
     * or a corrected source still produces a new version instead of being treated as unchanged.
     */
    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
