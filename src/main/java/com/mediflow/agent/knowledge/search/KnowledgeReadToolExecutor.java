package com.mediflow.agent.knowledge.search;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.index.KnowledgeIndexFilter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mediflow.agent.agent.booking.DoctorDirectory;
import com.mediflow.agent.agent.booking.DepartmentCatalog;
import com.mediflow.agent.agent.runtime.KnowledgeConsultation;
import com.mediflow.agent.agent.tool.ReadToolExecutor;
import com.mediflow.agent.agent.tool.ReadToolSpec;
import com.mediflow.agent.agent.tool.ReadToolSpecs;
import com.mediflow.agent.agent.task.TaskSnapshot;
import com.mediflow.agent.agent.tool.ToolCall;
import com.mediflow.agent.agent.tool.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The agent's read tool for hospital material, {@code searchHospitalKnowledge}.
 *
 * <p>Two distinctions this class exists to preserve:
 *
 * <ul>
 *   <li><b>"Nothing matched" and "could not look it up" are different results.</b> The first is a
 *       successful search with no hits and the model may say the material does not cover the
 *       question. The second is a failure, because an index that cannot be reached must never be
 *       reported as the hospital having no such rule — that would be a fabricated answer.
 *   <li><b>A passage that names a clinician is verified before it is used.</b> Retrieval matches text,
 *       and text does not stop naming a doctor who has since been deregistered or moved department.
 * </ul>
 *
 * <p>Slot availability is deliberately absent from this tool: inventory is live data that only the
 * appointment path may answer, and putting it in a searchable index would serve stale numbers.
 */
@Service
@Profile("mysql")
public final class KnowledgeReadToolExecutor implements ReadToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeReadToolExecutor.class);

    public static final String TOOL_NAME = KnowledgeConsultation.TOOL_NAME;

    /** Enough to answer from, few enough that the model is not handed a wall of text. */
    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 8;
    private static final int MAX_VISIBLE_SOURCES = 3;
    private static final int MAX_QUESTION_LENGTH = 1500;
    private static final Set<String> DOCTOR_PROFILE_HEADINGS = Set.of("专业方向", "日常接诊范围");

    private static final List<ReadToolSpec> TOOLS = List.of(ReadToolSpecs.SEARCH_HOSPITAL_KNOWLEDGE);

    private final KnowledgeRetriever retriever;
    private final DoctorDirectory doctors;
    private final DepartmentCatalog departments;

    public KnowledgeReadToolExecutor(KnowledgeRetriever retriever, DoctorDirectory doctors, DepartmentCatalog departments) {
        this.retriever = retriever;
        this.doctors = doctors;
        this.departments = departments;
    }

    @Override
    public List<ReadToolSpec> tools() {
        return TOOLS;
    }

    @Override
    public ToolResult execute(ToolCall call, TaskSnapshot task) {
        if (call == null || task == null) {
            return ToolResult.failure("INVALID_TOOL_REQUEST", "工具请求不完整");
        }
        if (!TOOL_NAME.equals(call.name())) {
            return ToolResult.failure("UNKNOWN_TOOL", "未知只读工具: " + call.name());
        }
        try {
            String patientQuestion = task.turns().isEmpty() ? null : task.turns().get(task.turns().size() - 1).userMessage();
            return search(call.arguments(), patientQuestion);
        } catch (IllegalArgumentException rejected) {
            return ToolResult.failure("INVALID_TOOL_ARGUMENT", rejected.getMessage());
        }
    }

    private ToolResult search(Map<String, Object> arguments, String patientQuestion) {
        String query = requiredQuery(arguments);
        String question = patientQuestion == null ? query : patientQuestion;
        KnowledgeDocType profileType = HospitalKnowledgeQuestion.profileType(question);
        KnowledgeDocType requestedType = docType(arguments);
        Long departmentId = positiveLong(arguments, "departmentId");
        Long doctorId = positiveLong(arguments, "doctorId");
        int topK = topK(arguments);

        ProfileScope profile = profileType == null ? null : resolveProfile(question, profileType);
        if (profileType != null && profile == null) {
            return ToolResult.success(Map.of("count", 0, "chunks", List.of(), "indexAvailable", true));
        }
        var coverage = HospitalKnowledgeQuestion.coverage(question);
        KnowledgeIndexFilter filter = profile == null
                ? new KnowledgeIndexFilter(coverage == null ? requestedType : coverage.type(), departmentId, doctorId)
                : profile.filter();

        // Look beyond the display limit, since a matching document can contain unrelated sections.
        KnowledgeRetriever.RetrievalResult result = retriever.search(profile == null ? query : question, filter, MAX_TOP_K);
        if (!result.indexAvailable()) {
            return ToolResult.failure("KNOWLEDGE_INDEX_UNAVAILABLE", "医院资料库暂时无法查询");
        }

        var distinctHeadings = new LinkedHashSet<String>();
        var used = result.chunks().stream()
                .filter(chunk -> profile == null ? coverage != null && coverage.includes(chunk) : profile.includes(chunk))
                .filter(this::clinicianIsCurrent)
                .filter(chunk -> distinctHeadings.add(chunk.heading()))
                .limit(Math.min(topK, MAX_VISIBLE_SOURCES)).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", used.size());
        data.put("chunks", used.stream().map(KnowledgeReadToolExecutor::toolPayload).toList());
        data.put("indexAvailable", true);
        data.put("model", result.model());
        if (!used.isEmpty()) {
            data.put("answer", "演示医院资料中写明：\n" + String.join("\n", used.stream()
                    .map(chunk -> "· " + chunk.content().split("\\R\\s*\\R", 2)[0].strip()).toList()));
        }
        return ToolResult.success(data);
    }

    private ProfileScope resolveProfile(String question, KnowledgeDocType type) {
        if (type == KnowledgeDocType.DOCTOR_PROFILE) {
            return doctors.findActiveClinicianMentionedIn(question)
                    .map(doctor -> new ProfileScope(new KnowledgeIndexFilter(type, doctor.departmentId(), doctor.id()),
                            DOCTOR_PROFILE_HEADINGS))
                    .orElse(null);
        }
        long departmentId = departments.findActiveDepartmentIdMentionedIn(question);
        return departmentId > 0
                ? new ProfileScope(new KnowledgeIndexFilter(type, departmentId, null), Set.of("看什么"))
                : null;
    }

    private record ProfileScope(KnowledgeIndexFilter filter, Set<String> headings) {
        boolean includes(KnowledgeRetriever.RetrievedChunk chunk) {
            return chunk.docType() == filter.docType()
                    && Objects.equals(chunk.doctorId(), filter.doctorId())
                    && Objects.equals(chunk.departmentId(), filter.departmentId())
                    && chunk.heading() != null && headings.contains(chunk.heading());
        }
    }

    /**
     * A passage may only be used when the clinician it names is still registered in the department it
     * names. Material that declares no doctor (rules, notices, department introductions) is unaffected:
     * there is nothing to verify, and treating it as unverified would silently discard most material.
     */
    private boolean clinicianIsCurrent(KnowledgeRetriever.RetrievedChunk chunk) {
        if (chunk.doctorId() == null) {
            return true;
        }
        boolean current = chunk.departmentId() != null
                && doctors.isActiveDoctorInDepartment(chunk.doctorId(), chunk.departmentId());
        if (!current) {
            logger.debug("Ignored a clinician profile outside the active directory: chunkId={}", chunk.chunkId());
        }
        return current;
    }

    /**
     * The agent reads passages as plain JSON, so the key set and its order are part of the tool
     * contract and must stay stable for prompts and recorded tool calls.
     */
    private static Map<String, Object> toolPayload(KnowledgeRetriever.RetrievedChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", chunk.chunkId());
        payload.put("documentId", chunk.documentId());
        payload.put("version", chunk.version());
        payload.put("docNo", chunk.docNo());
        payload.put("title", chunk.title());
        payload.put("docType", chunk.docType().name());
        payload.put("source", chunk.source());
        payload.put("publishedAt", chunk.publishedAt() == null ? "" : chunk.publishedAt().toString());
        payload.put("heading", chunk.heading() == null ? "" : chunk.heading());
        payload.put("content", chunk.content());
        return payload;
    }

    private static String requiredQuery(Map<String, Object> arguments) {
        String query = text(arguments, "query");
        if (query == null) {
            throw new IllegalArgumentException("请补充要查询的问题");
        }
        if (query.length() > MAX_QUESTION_LENGTH) throw new IllegalArgumentException("查询问题过长");
        return query;
    }

    private static KnowledgeDocType docType(Map<String, Object> arguments) {
        String value = text(arguments, "docType");
        return value == null ? null : KnowledgeDocType.from(value);
    }

    private static int topK(Map<String, Object> arguments) {
        Object value = arguments.get("topK");
        if (value == null) {
            return DEFAULT_TOP_K;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            if (parsed <= 0 || parsed > MAX_TOP_K) {
                throw new IllegalArgumentException("topK 必须在 1 到 " + MAX_TOP_K + " 之间");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("topK 必须是正整数");
        }
    }

    private static Long positiveLong(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.toString());
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " 必须是正整数");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
    }

    private static String text(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }
}
