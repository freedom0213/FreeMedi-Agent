package com.mediflow.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.AgentCallPolicy;
import com.mediflow.agent.agent.TaskAction;
import com.mediflow.agent.agent.TaskStatus;
import com.mediflow.agent.config.AgentSettings;
import com.mediflow.agent.knowledge.KnowledgeImporter;
import com.mediflow.agent.knowledge.KnowledgeIndex;
import com.mediflow.agent.knowledge.KnowledgeIndexFilter;
import com.mediflow.agent.knowledge.KnowledgeIndexer;
import com.mediflow.agent.knowledge.KnowledgeRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "mediflow.knowledge.index=memory",
        "mediflow.admin.username=admin-knowledge-test",
        "mediflow.admin.password=admin-knowledge-fixture-only"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql"})
@EnabledIfSystemProperty(named = "mediflow.test.db.url", matches = ".+")
class MySqlAdminKnowledgeTest {
    private static final String ADMIN_AUTH = "/api/v1/admin/auth";
    private static final String PATIENT_AUTH = "/api/v1/auth";
    private static final String DOCUMENTS = "/api/v1/admin/knowledge/documents";
    private static final String PREVIEW = "/api/v1/admin/knowledge/import-preview";
    private static final String ADMIN = "admin-knowledge-test";
    private static final String ADMIN_PASSWORD = "admin-knowledge-fixture-only";
    private static final String MARKDOWN = """
            ---
            doc_no: ADMIN-PREVIEW-FIXTURE
            title: 预约资料预览
            type: VISIT_NOTICE
            source: 门诊服务台
            ---

            ## 就诊凭证

            请持预约凭证。

            ## 到院时间

            请按预约时间到院。
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AgentCallPolicy agentCalls;
    @Autowired AgentSettings agentSettings;
    @Autowired AutowireCapableBeanFactory beanFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeImporter importer;
    @Autowired KnowledgeIndex index;
    @Autowired KnowledgeIndexer indexer;
    @Autowired KnowledgeRetriever retriever;
    @TempDir Path markdownDirectory;
    private static final com.sun.net.httpserver.HttpServer MODEL_SERVER = startModelServer();

    private static com.sun.net.httpserver.HttpServer startModelServer() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                var mapper = new ObjectMapper();
                var request = mapper.readTree(exchange.getRequestBody());
                if (!"Bearer fixture-agent-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                boolean tool = request.path("tool_choice").isObject();
                Map<String, Object> message = tool ? Map.of("role", "assistant", "tool_calls", java.util.List.of(
                        Map.of("id", "call_fixture", "type", "function", "function", Map.of("name", "findAvailableSlots",
                                "arguments", "{\"departmentName\":\"测试科室\"}"))))
                        : Map.of("role", "assistant", "content", mapper.writeValueAsString(Map.of(
                                "type", "REPLY", "message", request.path("model").asText())));
                byte[] response = mapper.writeValueAsBytes(Map.of("id", "completion_fixture", "object", "chat.completion",
                        "model", request.path("model").asText(), "choices", java.util.List.of(
                                Map.of("index", 0, "message", message, "finish_reason", tool ? "tool_calls" : "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void stopModelServer() { MODEL_SERVER.stop(0); }

    private static String modelUrl() { return "http://127.0.0.1:" + MODEL_SERVER.getAddress().getPort() + "/v1"; }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("mediflow.llm.api-key", () -> "");
        registry.add("mediflow.llm.base-url", MySqlAdminKnowledgeTest::modelUrl);
        registry.add("mediflow.llm.settings-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("spring.datasource.url", () -> System.getProperty("mediflow.test.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("mediflow.test.db.username", "root"));
        registry.add("spring.datasource.password", () -> System.getProperty("mediflow.test.db.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void onlyAdministratorsCanSignInAndUseTheManagementApi() throws Exception {
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        String patientPassword = "Mediflow-Demo-A!2026";
        mvc.perform(get(DOCUMENTS)).andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN_AUTH + "/login").param("username", ADMIN).param("password", ADMIN_PASSWORD))
                .andExpect(status().isForbidden());
        signIn(ADMIN_AUTH, patient, patientPassword, new MockHttpSession(), 401);
        signIn(PATIENT_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 401);

        var patientSession = signIn(PATIENT_AUTH, patient, patientPassword, new MockHttpSession(), 204);
        mvc.perform(get(DOCUMENTS).session(patientSession)).andExpect(status().isForbidden());
        var patientCsrf = csrf(ADMIN_AUTH, patientSession);
        mvc.perform(post(ADMIN_AUTH + "/logout").session(patientSession)
                        .header(patientCsrf.header(), patientCsrf.token()))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATIENT_AUTH + "/session").session(patientSession)).andExpect(status().isOk());

        var adminSession = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, patientSession, 204);
        mvc.perform(get(ADMIN_AUTH + "/session").session(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("资料管理员"));
        mvc.perform(get(PATIENT_AUTH + "/session").session(adminSession)).andExpect(status().isForbidden());
        mvc.perform(get(DOCUMENTS).session(adminSession)).andExpect(status().isOk());

        var adminCsrf = csrf(ADMIN_AUTH, adminSession);
        mvc.perform(post(ADMIN_AUTH + "/logout").session(adminSession)
                        .header(adminCsrf.header(), adminCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get(DOCUMENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void separatesPublicationFromIndexingAndDoesNotExposeRawIndexErrors() throws Exception {
        String docNo = "ADMIN-KNOWLEDGE-TEST";
        long archived = insertVersion(docNo, 1, "ARCHIVED");
        long published = insertVersion(docNo, 2, "PUBLISHED");
        insertVersion(docNo, 3, "DRAFT");
        insertChunk(archived, 1, 1, "INDEXED", null);
        insertChunk(published, 2, 1, "INDEXED", null);
        insertChunk(published, 2, 2, "FAILED", "无法访问 Qdrant（internal upstream URL）");
        insertChunk(published, 2, 3, "PENDING", null);
        insertChunk(published, 1, 1, "INDEXED", null);
        var session = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);

        mvc.perform(get(DOCUMENTS).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.document.docNo == '%s')].document.version".formatted(docNo)).value(2))
                .andExpect(jsonPath("$[?(@.document.docNo == '%s')].document.indexed".formatted(docNo)).value(1))
                .andExpect(jsonPath("$[?(@.document.docNo == '%s')].versionCount".formatted(docNo)).value(3));
        mvc.perform(get(DOCUMENTS + "/" + docNo).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.version").value(2))
                .andExpect(jsonPath("$.document.indexed").value(1))
                .andExpect(jsonPath("$.document.pending").value(1))
                .andExpect(jsonPath("$.document.failed").value(1))
                .andExpect(jsonPath("$.versions[0].version").value(3))
                .andExpect(jsonPath("$.chunks[0].retrievable").value(true))
                .andExpect(jsonPath("$.chunks[1].retrievable").value(false))
                .andExpect(jsonPath("$.chunks[1].failureReason").value("连接 Qdrant 失败，请检查向量库服务。"))
                .andExpect(jsonPath("$.chunks[2].retrievable").value(false));
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "1").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.chunks[0].embeddingStatus").value("INDEXED"))
                .andExpect(jsonPath("$.chunks[0].retrievable").value(false));
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "99").session(session))
                .andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS + "/MISSING-ADMIN-FIXTURE").session(session)).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void archivesOnlyTheConfirmedVersionAndRetainsChunksForHistory() throws Exception {
        String docNo = "ADMIN-ARCHIVE-TEST";
        insertVersion(docNo, 1, "ARCHIVED");
        long published = insertVersion(docNo, 2, "PUBLISHED");
        insertVersion(docNo, 3, "DRAFT");
        insertChunk(published, 2, 1, "INDEXED", null);
        var session = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, session);

        archive(docNo, "{\"version\":1}", session, token, 409);
        archive(docNo, "{\"version\":3}", session, token, 409);
        archive(docNo, "{\"version\":99}", session, token, 404);
        mvc.perform(get(DOCUMENTS + "/" + docNo).session(session))
                .andExpect(jsonPath("$.document.version").value(2))
                .andExpect(jsonPath("$.document.status").value("PUBLISHED"));

        archive(docNo, "{\"version\":2}", session, token, 204);
        archive(docNo, "{\"version\":2}", session, token, 204);
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "2").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.chunks[0].content").value("片段正文"))
                .andExpect(jsonPath("$.chunks[0].embeddingStatus").value("INDEXED"))
                .andExpect(jsonPath("$.chunks[0].retrievable").value(false));
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "3").session(session))
                .andExpect(jsonPath("$.document.status").value("DRAFT"));
    }

    @Test
    @Transactional
    void rejectsArchiveWithoutAnAdministratorCsrfTokenAndPositiveVersion() throws Exception {
        String docNo = "ADMIN-ARCHIVE-SECURITY";
        insertVersion(docNo, 1, "PUBLISHED");
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var adminToken = csrf(ADMIN_AUTH, admin);
        var anonymous = new MockHttpSession();
        archive(docNo, "{\"version\":1}", anonymous, csrf(ADMIN_AUTH, anonymous), 401);
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        var patientSession = signIn(PATIENT_AUTH, patient, "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        archive(docNo, "{\"version\":1}", patientSession, csrf(ADMIN_AUTH, patientSession), 403);
        mvc.perform(post(DOCUMENTS + "/" + docNo + "/archive").session(admin)
                        .contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isForbidden());
        archive(docNo, "{}", admin, adminToken, 400);
        archive(docNo, "{\"version\":0}", admin, adminToken, 400);
        mvc.perform(get(DOCUMENTS + "/" + docNo).session(admin))
                .andExpect(jsonPath("$.document.status").value("PUBLISHED"));
    }

    @Test
    @Transactional
    void previewsTheSameChunksAndVersionsAsTheImporterWithoutChangingPublishedMaterial() throws Exception {
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, admin);
        preview(bytes("\uFEFF" + MARKDOWN.replace("\n", "\r\n")), admin, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("ADMIN-PREVIEW-FIXTURE"))
                .andExpect(jsonPath("$.source").value("门诊服务台"))
                .andExpect(jsonPath("$.disposition").value("NEW_DOCUMENT"))
                .andExpect(jsonPath("$.proposedVersion").value(1))
                .andExpect(jsonPath("$.chunks.length()").value(2))
                .andExpect(jsonPath("$.chunks[0].heading").value("就诊凭证"))
                .andExpect(jsonPath("$.chunks[0].content").value("请持预约凭证。"))
                .andExpect(jsonPath("$.chunks[1].chunkNo").value(2));
        mvc.perform(get(DOCUMENTS + "/ADMIN-PREVIEW-FIXTURE").session(admin)).andExpect(status().isNotFound());

        Path file = markdownDirectory.resolve("preview-fixture.md");
        Files.writeString(file, MARKDOWN);
        assertThat(importer.importDirectory(markdownDirectory).imported()).isEqualTo(1);
        Files.writeString(file, MARKDOWN.replace("请持预约凭证。", "请出示预约凭证。"));
        assertThat(importer.importDirectory(markdownDirectory).imported()).isEqualTo(1);

        preview(bytes(MARKDOWN), admin, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"))
                .andExpect(jsonPath("$.duplicate.version").value(1))
                .andExpect(jsonPath("$.duplicate.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.published.version").value(2));
        preview(bytes(MARKDOWN.replace("门诊服务台", "测试资料部门")), admin, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("NEW_VERSION"))
                .andExpect(jsonPath("$.proposedVersion").value(3))
                .andExpect(jsonPath("$.published.version").value(2));
        mvc.perform(get(DOCUMENTS + "/ADMIN-PREVIEW-FIXTURE").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.version").value(2))
                .andExpect(jsonPath("$.document.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.chunks[0].content").value("请出示预约凭证。"))
                .andExpect(jsonPath("$.chunks[0].embeddingStatus").value("PENDING"));
    }

    @Test
    void rejectsInvalidMarkdownAndPreventsPatientPreviewRequests() throws Exception {
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, admin);
        var anonymous = new MockHttpSession();
        preview(bytes(MARKDOWN), anonymous, csrf(ADMIN_AUTH, anonymous)).andExpect(status().isUnauthorized());
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        var patientSession = signIn(PATIENT_AUTH, patient, "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        preview(bytes(MARKDOWN), patientSession, csrf(ADMIN_AUTH, patientSession)).andExpect(status().isForbidden());
        mvc.perform(post(PREVIEW).session(admin).contentType("text/markdown").content(MARKDOWN))
                .andExpect(status().isForbidden());

        preview(new byte[256 * 1024 + 1], admin, token).andExpect(status().isPayloadTooLarge());
        preview(new byte[]{(byte) 0xc3, 0x28}, admin, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("文件编码无效，请另存为 UTF-8 后再预览"));
        preview(bytes(MARKDOWN.replace("source: 门诊服务台\n", "")), admin, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Markdown 文件 缺少必填元信息：source"));
        preview(bytes(MARKDOWN.replace("source: 门诊服务台", "source: 门诊服务台\nsource: 重复")),
                admin, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Markdown 文件 重复填写了元信息：source"));
        preview(bytes(MARKDOWN.replace("source: 门诊服务台", "source: 门诊服务台\ndepartment_id: 90000000")),
                admin, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("department_id 对应的科室不存在，请核对编号"));
        preview(bytes(MARKDOWN.replace("请持预约凭证。", "").replace("请按预约时间到院。", "")),
                admin, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Markdown 正文没有可切分的片段，请为章节添加正文"));
    }

    private ResultActions preview(byte[] payload, MockHttpSession session, Csrf token) throws Exception {
        return mvc.perform(post(PREVIEW).session(session).header(token.header(), token.token())
                .contentType("text/markdown; charset=UTF-8").content(payload));
    }

    private static byte[] bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Transactional
    void publishedVersionSwitchesOnlyAfterAllDraftVectorsAreVerified() throws Exception {
        String docNo = "ADMIN-PREVIEW-FIXTURE";
        Files.writeString(markdownDirectory.resolve("current.md"), MARKDOWN);
        assertThat(importer.importDirectory(markdownDirectory).imported()).isEqualTo(1);
        indexer.indexPending();
        String revised = MARKDOWN.replace("请持预约凭证。", "请使用电子预约凭证。");
        var session = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, session);
        var inspected = preview(bytes(revised), session, token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String hash = json.readTree(inspected).get("contentHash").asText();
        saveDraft(revised, "wrong-hash", 1, session, token).andExpect(status().isConflict());
        for (int replay = 0; replay < 2; replay++) {
            saveDraft(revised, hash, 1, session, token).andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
        }
        mvc.perform(get(DOCUMENTS + "/" + docNo).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.version").value(1))
                .andExpect(jsonPath("$.versions.length()").value(2));
        String draft = DOCUMENTS + "/" + docNo + "/versions/2";
        draftAction(draft + "/publish", "{\"expectedPublishedVersion\":1}", session, token)
                .andExpect(status().isConflict());
        draftAction(draft + "/index", "", session, token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(false));
        draftAction(draft + "/index", "", session, token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.finished").value(true));

        long draftId = jdbc.queryForObject("SELECT id FROM knowledge_document WHERE doc_no = ? AND version = 2",
                Long.class, docNo);
        assertThat(retriever.search("预约凭证", KnowledgeIndexFilter.none(), 5).chunks())
                .anyMatch(chunk -> chunk.docNo().equals(docNo) && chunk.version() == 1)
                .noneMatch(chunk -> chunk.documentId() == draftId);
        index.removeVersion(draftId, 2);
        draftAction(draft + "/publish", "{\"expectedPublishedVersion\":1}", session, token)
                .andExpect(status().isConflict());
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "2").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("DRAFT"))
                .andExpect(jsonPath("$.document.pending").value(2));
        draftAction(draft + "/index", "", session, token).andExpect(status().isOk());
        draftAction(draft + "/index", "", session, token).andExpect(status().isOk());
        draftAction(draft + "/publish", "{\"expectedPublishedVersion\":0}", session, token)
                .andExpect(status().isConflict());
        for (int replay = 0; replay < 2; replay++) {
            draftAction(draft + "/publish", "{\"expectedPublishedVersion\":1}", session, token)
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get(DOCUMENTS + "/" + docNo).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.version").value(2))
                .andExpect(jsonPath("$.document.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versions[1].status").value("ARCHIVED"));
        assertThat(retriever.search("预约凭证", KnowledgeIndexFilter.none(), 5).chunks())
                .anyMatch(chunk -> chunk.documentId() == draftId);

        String nextMarkdown = revised.replace("请按预约时间到院。", "请按新预约时间到院。");
        var nextPreview = preview(bytes(nextMarkdown), session, token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nextHash = json.readTree(nextPreview).get("contentHash").asText();
        saveDraft(nextMarkdown, nextHash, 2, session, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        String nextDraft = DOCUMENTS + "/" + docNo + "/versions/3";
        draftAction(nextDraft + "/index", "", session, token).andExpect(status().isOk());
        draftAction(nextDraft + "/index", "", session, token).andExpect(status().isOk());
        archive(docNo, "{\"version\":2}", session, token, 204);
        draftAction(nextDraft + "/publish", "{\"expectedPublishedVersion\":2}", session, token)
                .andExpect(status().isConflict());
        mvc.perform(get(DOCUMENTS + "/" + docNo).param("version", "3").session(session))
                .andExpect(jsonPath("$.document.status").value("DRAFT"));
    }

    private ResultActions saveDraft(String markdown, String hash, int expected, MockHttpSession session, Csrf token)
            throws Exception {
        return mvc.perform(post("/api/v1/admin/knowledge/import-drafts").session(session)
                .header(token.header(), token.token()).header("X-Preview-Hash", hash)
                .header("X-Expected-Latest-Version", Integer.toString(expected))
                .header("X-Expected-Published-Version", Integer.toString(expected))
                .contentType("text/markdown").content(bytes(markdown)));
    }

    private ResultActions draftAction(String endpoint, String payload, MockHttpSession session, Csrf token)
            throws Exception {
        return mvc.perform(post(endpoint).session(session).header(token.header(), token.token())
                .contentType("application/json").content(payload));
    }

    @Test
    @Transactional
    void adminTracePagesSanitizedAttemptsAndDeniesPatientAccess() throws Exception {
        String taskNo = "ADMIN-TRACE-FIXTURE";
        jdbc.update("INSERT INTO agent_session(patient_id, title, status) VALUES (1001, ?, 'ACTIVE')", taskNo);
        jdbc.update("""
                INSERT INTO agent_task(task_no, session_id, patient_id, task_type, status, current_turn_no, collected_facts)
                SELECT ?, id, 1001, 'APPOINTMENT_BOOKING', 'COMPLETED', 1, JSON_OBJECT()
                FROM agent_session WHERE patient_id = 1001 AND title = ?
                """, taskNo, taskNo);
        long id = jdbc.queryForObject("SELECT id FROM agent_task WHERE task_no = ?", Long.class, taskNo);
        for (int sequence = 1; sequence <= 51; sequence++) {
            boolean failed = sequence == 1;
            String reason = failed ? "TOOL_TIMEOUT" : null;
            var action = new TaskAction("READ_TOOL_ATTEMPT", TaskStatus.SEARCHING, 1, 1,
                    failed ? 1 : 2, failed ? "FAILED" : "SUCCEEDED", reason, failed ? 5000 : 29,
                    Map.of("tool", "findAvailableSlots", "resultCount", 3, "query", "private fixture content"));
            jdbc.update("""
                    INSERT INTO agent_state_event(task_id, event_no, event_type, to_status, actor_type, reason_code, metadata)
                    VALUES (?, ?, 'READ_TOOL_ATTEMPT', 'SEARCHING', 'SYSTEM', ?, ?)
                    """, id, sequence, reason, json.writeValueAsString(action));
        }
        String list = "/api/v1/admin/agent/tasks";
        String trace = list + "/" + taskNo + "/trace";
        mvc.perform(get(trace)).andExpect(status().isUnauthorized());
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        var patientSession = signIn(PATIENT_AUTH, patient, "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        mvc.perform(get(list).session(patientSession)).andExpect(status().isForbidden());
        mvc.perform(get(trace).session(patientSession)).andExpect(status().isForbidden());
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        mvc.perform(get(list).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.taskId == '%s')].status".formatted(taskNo)).value("COMPLETED"));
        var first = mvc.perform(get(trace).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(50))
                .andExpect(jsonPath("$.entries[0].tool").value("findAvailableSlots"))
                .andExpect(jsonPath("$.entries[0].reason").value("TOOL_TIMEOUT"))
                .andExpect(jsonPath("$.entries[0].durationMs").value(5000))
                .andExpect(jsonPath("$.entries[1].attempt").value(2))
                .andExpect(jsonPath("$.nextAfter").value(50)).andReturn();
        assertThat(first.getResponse().getContentAsString()).doesNotContain("private fixture content", "patientId");
        mvc.perform(get(trace).param("after", "50").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].sequence").value(51));
        mvc.perform(get(list + "/NO-SUCH-TASK/trace").session(admin)).andExpect(status().isNotFound());
    }

    private void archive(String docNo, String payload, MockHttpSession session, Csrf token, int expected)
            throws Exception {
        mvc.perform(post(DOCUMENTS + "/" + docNo + "/archive").session(session)
                        .header(token.header(), token.token()).contentType("application/json").content(payload))
                .andExpect(status().is(expected));
    }

    @Test
    void toolRegistryOnlyExposesRegisteredModelToolsToAdmins() throws Exception {
        String path = "/api/v1/admin/agent/tools";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        var patientSession = signIn(PATIENT_AUTH, patient, "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        mvc.perform(get(path).session(patientSession)).andExpect(status().isForbidden());
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("findAvailableSlots"))
                .andExpect(jsonPath("$[0].parameters.length()").value(9))
                .andExpect(jsonPath("$[0].parameters[?(@.required == true)]").isEmpty())
                .andExpect(jsonPath("$[1].name").value("getAppointmentRules"))
                .andExpect(jsonPath("$[1].parameters").isEmpty())
                .andExpect(jsonPath("$[2].name").value("searchHospitalKnowledge"))
                .andExpect(jsonPath("$[2].parameters[0].name").value("query"))
                .andExpect(jsonPath("$[2].parameters[0].type").value("STRING"))
                .andExpect(jsonPath("$[2].parameters[0].required").value(true));
        var token = csrf(ADMIN_AUTH, admin);
        mvc.perform(post(path).session(admin).header(token.header(), token.token()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void reportsTheActiveDemoModelAndCallPolicyToAdministratorsOnly() throws Exception {
        String path = "/api/v1/admin/agent/model-config";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String patient = jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1001", String.class);
        var patientSession = signIn(PATIENT_AUTH, patient, "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        mvc.perform(get(path).session(patientSession)).andExpect(status().isForbidden());
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var response = mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEMO"))
                .andExpect(jsonPath("$.provider").doesNotExist()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsString());
        assertThat(json.treeToValue(body.get("callPolicy"), AgentCallPolicy.Settings.class))
            .isEqualTo(agentCalls.settings());
        assertThat(body.has("apiKey")).isFalse();
        var token = csrf(ADMIN_AUTH, admin);
        mvc.perform(post(path).session(admin).header(token.header(), token.token()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void managesTestedModelRevisionsAndToolPolicyAcrossRuntimeReload() throws Exception {
        String models = "/api/v1/admin/agent/model-config";
        String tools = "/api/v1/admin/agent/tools/findAvailableSlots";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, admin);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientToken = csrf(PATIENT_AUTH, patient);
        String setting = "{\"enabled\":false,\"timeoutMs\":1400,\"expectedRevision\":0}";
        mvc.perform(put(tools).session(admin).contentType("application/json").content(setting))
                .andExpect(status().isForbidden());
        mvc.perform(put(tools).session(patient).header(patientToken.header(), patientToken.token())
                        .contentType("application/json").content(setting)).andExpect(status().isForbidden());

        var initial = agentSettings.modelView();
        var originalSnapshot = agentSettings.snapshot();
        String profileId = null;
        try {
            String draft = json.writeValueAsString(Map.of("expectedId", 0, "label", "测试模型", "baseUrl", modelUrl(),
                    "modelName", "fixture-one", "apiKey", "fixture-agent-key"));
            var saved = mvc.perform(post(models + "/models").session(admin).header(token.header(), token.token())
                            .contentType("application/json").content(draft)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("DEMO")).andReturn().getResponse().getContentAsString();
            assertThat(saved).doesNotContain("fixture-agent-key", "encryptedKey");
            var first = agentSettings.modelView().models().get(0);
            profileId = first.profileId();
            var encrypted = jdbc.queryForObject("SELECT encrypted_key FROM agent_model_revision WHERE id = ?", byte[].class, first.id());
            assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain("fixture-agent-key");
            String activation = json.writeValueAsString(Map.of("id", first.id(), "expectedRevision", initial.revision()));
            mvc.perform(post(models + "/activate").session(admin).header(token.header(), token.token())
                            .contentType("application/json").content(activation)).andExpect(status().isConflict());
            mvc.perform(post(models + "/models/" + first.id() + "/test").session(admin).header(token.header(), token.token()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.models[0].probeCode").value("PASSED"));
            mvc.perform(post(models + "/activate").session(admin).header(token.header(), token.token())
                            .contentType("application/json").content(activation))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active.modelName").value("fixture-one"));

            var created = mvc.perform(post("/api/v1/tasks").session(patient).header(patientToken.header(), patientToken.token()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String task = "/api/v1/tasks/" + json.readTree(created).get("taskId").asText();
            mvc.perform(post(task + "/messages").session(patient).header(patientToken.header(), patientToken.token())
                            .contentType("application/json").content("{\"message\":\"测试新模型\",\"clientRequestId\":\"agent-settings-fixture\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("fixture-one"));
            mvc.perform(put(tools).session(admin).header(token.header(), token.token())
                            .contentType("application/json").content(setting))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(false));
            mvc.perform(put(tools).session(admin).header(token.header(), token.token())
                            .contentType("application/json").content(setting)).andExpect(status().isConflict());
            mvc.perform(post(task + "/requirements").session(patient).header(patientToken.header(), patientToken.token())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("暂时无法查询号源，已保留原预约条件，请稍后再试。"));
            var reloaded = beanFactory.createBean(AgentSettings.class);
            assertThat(reloaded.snapshot().modelVersion()).isEqualTo(first.profileId() + "/v1");
            assertThat(reloaded.snapshot().toolEnabled("findAvailableSlots")).isFalse();
            assertThat(reloaded.snapshot().toolTimeout("findAvailableSlots")).isEqualTo(1400L);
            assertThat(originalSnapshot.toolEnabled("findAvailableSlots")).isTrue();
            assertThat(originalSnapshot.modelVersion()).isEqualTo("environment");

            agentSettings.saveModel(profileId, first.id(), "测试模型", modelUrl(), "fixture-two", null, Long.parseLong(
                    jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", String.class, ADMIN)));
            assertThat(agentSettings.modelView().active().id()).isEqualTo(first.id());
            assertThat(agentSettings.modelView().models().get(0).probeCode()).isNull();
        } finally {
            long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
            agentSettings.activate(null, agentSettings.modelView().revision(), adminId);
            var tool = agentSettings.toolView().get(0);
            agentSettings.updateTool(tool.name(), true, null, tool.revision(), adminId);
            if (profileId != null) jdbc.update("DELETE FROM agent_model_revision WHERE profile_id = ?", profileId);
        }
    }

    private MockHttpSession signIn(String endpoint, String username, String password, MockHttpSession session,
                                   int expectedStatus) throws Exception {
        var csrf = csrf(endpoint, session);
        return (MockHttpSession) mvc.perform(post(endpoint + "/login").session(session)
                        .header(csrf.header(), csrf.token()).param("username", username).param("password", password))
                .andExpect(status().is(expectedStatus)).andReturn().getRequest().getSession(false);
    }

    private Csrf csrf(String endpoint, MockHttpSession session) throws Exception {
        var body = mvc.perform(get(endpoint + "/csrf").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var payload = json.readTree(body);
        return new Csrf(payload.get("headerName").asText(), payload.get("token").asText());
    }

    private long insertVersion(String docNo, int version, String status) {
        jdbc.update("""
                INSERT INTO knowledge_document(doc_no, title, doc_type, source, version, status, content_hash)
                VALUES (?, '预约挂号规则', 'APPOINTMENT_RULE', '门诊服务台', ?, ?, SHA2(?, 256))
                """, docNo, version, status, docNo + version);
        return jdbc.queryForObject("SELECT id FROM knowledge_document WHERE doc_no = ? AND version = ?",
                Long.class, docNo, version);
    }

    private void insertChunk(long documentId, int version, int number, String state, String error) {
        jdbc.update("""
                INSERT INTO knowledge_chunk(document_id, version, chunk_no, heading, content, char_count,
                                            embedding_status, index_error)
                VALUES (?, ?, ?, '预约须知', '片段正文', 4, ?, ?)
                """, documentId, version, number, state, error);
    }

    private record Csrf(String header, String token) {}
}
