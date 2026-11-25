package com.mediflow.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.AgentCallPolicy;
import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.TaskAction;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskStatus;
import com.mediflow.agent.appointment.AdminCatalogService;
import com.mediflow.agent.appointment.AppointmentWriteService;
import com.mediflow.agent.appointment.AppointmentQueryService;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired AgentRuntime agentRuntime;
    @Autowired AdminCatalogService adminCatalog;
    @Autowired AppointmentWriteService appointmentWriter;
    @Autowired AutowireCapableBeanFactory beanFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeImporter importer;
    @Autowired KnowledgeIndex index;
    @Autowired KnowledgeIndexer indexer;
    @Autowired KnowledgeRetriever retriever;
    @TempDir Path markdownDirectory;
    private static final java.util.concurrent.atomic.AtomicInteger OBSERVATION_RETRY = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.CountDownLatch PROMPT_STARTED = new java.util.concurrent.CountDownLatch(1);
    private static final java.util.concurrent.CountDownLatch PROMPT_RELEASE = new java.util.concurrent.CountDownLatch(1);
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
                if (request.path("model").asText().equals("observation-one")
                        && request.path("messages").toString().contains("obs-retry-fixture")
                        && OBSERVATION_RETRY.getAndIncrement() == 0) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                boolean tool = request.path("tool_choice").isObject();
                var promptMessage = request.path("model").asText().startsWith("prompt-fixture") ? promptFixture(request, mapper) : null;
                tool = promptMessage == null ? tool : promptMessage.containsKey("tool_calls");
                Map<String, Object> message = promptMessage != null ? promptMessage : tool ? Map.of("role", "assistant", "tool_calls", java.util.List.of(
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

    private static Map<String, Object> promptFixture(com.fasterxml.jackson.databind.JsonNode request, ObjectMapper mapper)
            throws java.io.IOException {
        var messages = request.path("messages");
        String system = messages.path(0).path("content").asText();
        String input = "";
        for (var message : messages) {
            String content = message.path("content").asText();
            if (content.contains("当前用户消息：")) input = content.substring(content.lastIndexOf("当前用户消息："));
        }
        if (input.isEmpty()) return null;
        if (input.contains("挂起观察")) {
            PROMPT_STARTED.countDown();
            try {
                if (!PROMPT_RELEASE.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Prompt request was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(interrupted);
            }
        }
        if (input.contains("最近的可约号源")) return promptTool("findAvailableSlots", "{\"departmentName\":\"呼吸内科\"}");
        if (input.contains("带什么证件")) return promptTool("searchHospitalKnowledge", "{\"query\":\"就诊证件\"}");
        Map<String, Object> output;
        if (input.contains("呼吸困难")) {
            output = Map.of("type", "REPLY", "message", "请立即联系急救。");
        } else if (input.contains("确认预约")) {
            output = Map.of("type", "REPLY", "message", system.contains("故意误报") ? "已经预约成功。"
                    : system.contains("先比较候选") ? "请先比较候选，再在页面点击确认。" : "请在页面点击确认。");
        } else {
            String guidance = system.contains("表达标记二") ? "表达标记二" : system.contains("表达标记一") ? "表达标记一" : "内置表达";
            output = Map.of("type", "ASK_USER", "question", guidance + "。您愿意补充就诊需求吗？");
        }
        return Map.of("role", "assistant", "content", mapper.writeValueAsString(output));
    }

    private static Map<String, Object> promptTool(String name, String arguments) {
        return Map.of("role", "assistant", "tool_calls", java.util.List.of(
                Map.of("id", "call_prompt_fixture", "type", "function", "function", Map.of("name", name, "arguments", arguments))));
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
        mvc.perform(get(DOCUMENTS).session(patientSession)).andExpect(status().isUnauthorized());
        var patientCsrf = csrf(PATIENT_AUTH, patientSession);
        mvc.perform(post(ADMIN_AUTH + "/logout").session(patientSession)
                        .header(patientCsrf.header(), patientCsrf.token()))
                .andExpect(status().isForbidden());
        var anonymousAdminCsrf = csrf(ADMIN_AUTH, patientSession);
        mvc.perform(post(ADMIN_AUTH + "/logout").session(patientSession)
                        .header(anonymousAdminCsrf.header(), anonymousAdminCsrf.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATIENT_AUTH + "/session").session(patientSession)).andExpect(status().isOk());

        var adminSession = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, patientSession, 204);
        mvc.perform(get(ADMIN_AUTH + "/session").session(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("资料管理员"));
        mvc.perform(get(PATIENT_AUTH + "/session").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get(DOCUMENTS).session(adminSession)).andExpect(status().isOk());

        var adminCsrf = csrf(ADMIN_AUTH, adminSession);
        mvc.perform(post(ADMIN_AUTH + "/logout").session(adminSession)
                        .header(adminCsrf.header(), adminCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get(DOCUMENTS).session(adminSession)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATIENT_AUTH + "/session").session(adminSession)).andExpect(status().isOk());

        signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, adminSession, 204);
        mvc.perform(post(PATIENT_AUTH + "/logout").session(adminSession)
                        .header(patientCsrf.header(), patientCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get(PATIENT_AUTH + "/session").session(adminSession)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN_AUTH + "/session").session(adminSession)).andExpect(status().isOk());

        signIn(PATIENT_AUTH, patient, patientPassword, adminSession, 204);
        mvc.perform(get(PATIENT_AUTH + "/session").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get(ADMIN_AUTH + "/session").session(adminSession)).andExpect(status().isOk());
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
        archive(docNo, "{\"version\":1}", patientSession, csrf(ADMIN_AUTH, patientSession), 401);
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
        preview(bytes(MARKDOWN), patientSession, csrf(ADMIN_AUTH, patientSession)).andExpect(status().isUnauthorized());
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
        mvc.perform(get(list).session(patientSession)).andExpect(status().isUnauthorized());
        mvc.perform(get(trace).session(patientSession)).andExpect(status().isUnauthorized());
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
        mvc.perform(get(path).session(patientSession)).andExpect(status().isUnauthorized());
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
        mvc.perform(get(path).session(patientSession)).andExpect(status().isUnauthorized());
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

    @Test
    void observesRealDecisionAttemptsAcrossModelVersionsAndKeepsRetriesSeparate() throws Exception {
        String path = "/api/v1/admin/observability/model-calls";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientToken = csrf(PATIENT_AUTH, patient);
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String range = "?from=" + today + "&to=" + today;
        mvc.perform(get(path + range).session(patient)).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/attempts" + range)).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "?from=2026-01-01&to=2026-02-01").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(path + "/attempts" + range + "&before=invalid").session(admin)).andExpect(status().isBadRequest());
        var created = catalogPost("/api/v1/tasks", patient, patientToken, null).andExpect(status().isCreated()).andReturn();
        String taskId = json.readTree(created.getResponse().getContentAsByteArray()).get("taskId").asText();
        String messages = "/api/v1/tasks/" + taskId + "/messages";
        catalogPost(messages, patient, patientToken, Map.of("message", "想咨询挂号", "clientRequestId", "obs-demo"))
                .andExpect(status().isOk());
        mvc.perform(get(path + range).session(admin).param("model", "demo:environment"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.attempts").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.models[0].model.mode").value("demo"));

        long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        agentSettings.saveModel(null, 0, "观测测试模型", modelUrl(), "observation-one", "fixture-agent-key", adminId);
        var first = agentSettings.modelView().models().stream().filter(model -> model.modelName().equals("observation-one"))
                .findFirst().orElseThrow();
        String key1 = "external:" + first.profileId() + "/v1";
        String key2 = "external:" + first.profileId() + "/v2";
        try {
            agentSettings.testModel(first.id(), adminId);
            agentSettings.activate(first.id(), agentSettings.modelView().revision(), adminId);
            mvc.perform(get(path + range).session(admin).param("model", key1)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totals.attempts").value(0)).andExpect(jsonPath("$.totals.p95Ms").isEmpty());
            OBSERVATION_RETRY.set(0);
            catalogPost(messages, patient, patientToken, Map.of("message", "obs-retry-fixture", "clientRequestId", "obs-v1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("observation-one"));
            assertThat(OBSERVATION_RETRY.get()).isEqualTo(2);
            mvc.perform(get(path + range).session(admin).param("model", key1)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totals.attempts").value(2)).andExpect(jsonPath("$.totals.retries").value(1))
                    .andExpect(jsonPath("$.totals.succeeded").value(1)).andExpect(jsonPath("$.totals.failed").value(1))
                    .andExpect(jsonPath("$.totals.averageMs").isNumber()).andExpect(jsonPath("$.totals.p95Ms").isNumber())
                    .andExpect(jsonPath("$.trend[0].date").value(today)).andExpect(jsonPath("$.trend[0].failed").value(1))
                    .andExpect(jsonPath("$.reasons[0].code").value("MODEL_TRANSIENT"))
                    .andExpect(jsonPath("$.models[0].model.modelName").value("observation-one"));
            var failed = mvc.perform(get(path + "/attempts" + range).session(admin).param("model", key1).param("outcome", "FAILED"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].taskId").value(taskId)).andExpect(jsonPath("$.items[0].attempt").value(1))
                    .andExpect(jsonPath("$.items[0].reason").value("MODEL_TRANSIENT")).andReturn();
            assertThat(failed.getResponse().getContentAsString()).doesNotContain("obs-retry-fixture", "fixture-agent-key");

            agentSettings.saveModel(first.profileId(), first.id(), "观测测试模型", modelUrl(), "observation-two", null, adminId);
            var second = agentSettings.modelView().models().stream().filter(model -> model.modelName().equals("observation-two"))
                    .findFirst().orElseThrow();
            agentSettings.testModel(second.id(), adminId);
            agentSettings.activate(second.id(), agentSettings.modelView().revision(), adminId);
            catalogPost(messages, patient, patientToken, Map.of("message", "继续咨询挂号", "clientRequestId", "obs-v2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("observation-two"));
            mvc.perform(get(path + range).session(admin).param("model", key2)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totals.attempts").value(1)).andExpect(jsonPath("$.totals.failed").value(0))
                    .andExpect(jsonPath("$.models[0].model.key").value(key2))
                    .andExpect(jsonPath("$.options[?(@.key == '" + key1 + "')].modelName").value(org.hamcrest.Matchers.hasItem("observation-one")));
        } finally {
            agentSettings.activate(null, agentSettings.modelView().revision(), adminId);
            jdbc.update("DELETE FROM agent_model_revision WHERE profile_id = ?", first.profileId());
        }
    }

    @Test
    void modelObservationsRespectHospitalDayBoundariesAndPageIdenticalTimestamps() throws Exception {
        String path = "/api/v1/admin/observability/model-calls";
        String range = "?from=2026-01-04&to=2026-01-04";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var created = catalogPost("/api/v1/tasks", patient, csrf(PATIENT_AUTH, patient), null)
                .andExpect(status().isCreated()).andReturn();
        String task = json.readTree(created.getResponse().getContentAsByteArray()).get("taskId").asText();
        long taskRow = jdbc.queryForObject("SELECT id FROM agent_task WHERE task_no = ?", Long.class, task);
        String insert = """
                INSERT INTO agent_state_event(task_id, event_no, to_status, event_type, actor_type, reason_code, metadata, created_at)
                VALUES (?, ?, 'UNDERSTANDING', 'MODEL_DECISION', 'SYSTEM', ?, ?, ?)
                """;
        for (int index = 0; index < 51; index++) {
            jdbc.update(insert, taskRow, index + 100, null, json.writeValueAsString(Map.of("outcome", "SUCCEEDED",
                    "attempt", 1, "durationMs", index, "summary", Map.of("adapter", "DeepSeekDecisionModel",
                            "modelVersion", "environment"))), "2026-01-03 16:00:00.000");
        }
        String failure = "{\"outcome\":\"FAILED\",\"attempt\":1,\"summary\":{}}";
        jdbc.update(insert, taskRow, 151, "private fixture string", failure, "2026-01-04 15:59:59.999");
        jdbc.update(insert, taskRow, 152, "MODEL_TIMEOUT", failure, "2026-01-04 16:00:00.000");
        mvc.perform(get(path + range).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.attempts").value(52)).andExpect(jsonPath("$.totals.averageMs").value(25.0))
                .andExpect(jsonPath("$.totals.p95Ms").value(48)).andExpect(jsonPath("$.trend[0].succeeded").value(51))
                .andExpect(jsonPath("$.reasons[0].code").isEmpty());
        var first = mvc.perform(get(path + "/attempts" + range).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50)).andExpect(jsonPath("$.items[0].modelKey").value("unspecified:unrecorded"))
                .andExpect(jsonPath("$.items[0].durationMs").isEmpty()).andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsByteArray()).get("nextBefore").asText();
        mvc.perform(get(path + "/attempts" + range).session(admin).param("before", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].durationMs").value(1)).andExpect(jsonPath("$.items[1].durationMs").value(0))
                .andExpect(jsonPath("$.nextBefore").isEmpty());
    }

    @Test
    void versionsPromptGuidanceAndKeepsTrialsOutOfPatientTasks() throws Exception {
        String path = "/api/v1/admin/agent/prompt-templates";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, admin);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientToken = csrf(PATIENT_AUTH, patient);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).session(patient)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveVersion").value("builtin-v1")).andExpect(jsonPath("$.trialAvailable").value(false));

        long first = savePrompt(path, admin, token, "表达标记一", "先比较候选");
        long second = savePrompt(path, admin, token, "表达标记二", "先比较候选");
        long rejected = savePrompt(path, admin, token, "", "故意误报");
        long revision = promptRevision(path, admin);
        catalogPost(path + "/versions/" + first + "/trial", admin, token, null).andExpect(status().isConflict());
        catalogPost(path + "/activate", admin, token, Map.of("id", first, "expectedRevision", revision))
                .andExpect(status().isConflict());

        long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        agentSettings.saveModel(null, 0, "模板试跑模型", modelUrl(), "prompt-fixture", "fixture-agent-key", adminId);
        var model = agentSettings.modelView().models().stream().filter(row -> row.modelName().equals("prompt-fixture")).findFirst().orElseThrow();
        var heldRequest = Executors.newSingleThreadExecutor();
        try {
            agentSettings.testModel(model.id(), adminId);
            agentSettings.activate(model.id(), agentSettings.modelView().revision(), adminId);
            long tasksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Long.class);
            long toolsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call", Long.class);
            long appointmentsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Long.class);
            catalogPost(path + "/versions/" + first + "/trial", admin, token, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.versions[?(@.id == " + first + ")].canActivate").value(org.hamcrest.Matchers.hasItem(true)))
                    .andExpect(jsonPath("$.versions[?(@.id == " + first + ")].report.cases[?(@.key == 'CONFIRM')].candidate.reply")
                            .value(org.hamcrest.Matchers.hasItem("请先比较候选，再在页面点击确认。")));
            catalogPost(path + "/versions/" + second + "/trial", admin, token, null).andExpect(status().isOk());
            catalogPost(path + "/versions/" + rejected + "/trial", admin, token, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.versions[?(@.id == " + rejected + ")].canActivate").value(org.hamcrest.Matchers.hasItem(false)));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Long.class)).isEqualTo(tasksBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call", Long.class)).isEqualTo(toolsBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Long.class)).isEqualTo(appointmentsBefore);
            catalogPost(path + "/activate", admin, token, Map.of("id", rejected, "expectedRevision", revision))
                    .andExpect(status().isConflict());

            var created = catalogPost("/api/v1/tasks", patient, patientToken, null).andExpect(status().isCreated()).andReturn();
            String task = json.readTree(created.getResponse().getContentAsByteArray()).get("taskId").asText();
            String messages = "/api/v1/tasks/" + task + "/messages";
            catalogPost(messages, patient, patientToken, Map.of("message", "请给个就诊方向。", "clientRequestId", "prompt-built-in"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("内置表达。您愿意补充就诊需求吗？"));
            catalogPost(path + "/activate", admin, token, Map.of("id", first, "expectedRevision", revision))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveVersion").value("v" + first));

            var inFlight = heldRequest.submit(() -> catalogPost(messages, patient, patientToken,
                    Map.of("message", "想了解就诊方向，挂起观察。", "clientRequestId", "prompt-v1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("表达标记一。您愿意补充就诊需求吗？")));
            assertThat(PROMPT_STARTED.await(6, TimeUnit.SECONDS)).isTrue();
            catalogPost(path + "/activate", admin, token, Map.of("id", second, "expectedRevision", revision))
                    .andExpect(status().isConflict());
            catalogPost(path + "/activate", admin, token, Map.of("id", second, "expectedRevision", revision + 1))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveVersion").value("v" + second));
            PROMPT_RELEASE.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
            catalogPost(messages, patient, patientToken, Map.of("message", "想继续了解就诊方向。", "clientRequestId", "prompt-v2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("表达标记二。您愿意补充就诊需求吗？"));
            catalogPost(path + "/activate", admin, token, Map.of("id", first, "expectedRevision", revision + 2))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveVersion").value("v" + first));
            catalogPost(messages, patient, patientToken, Map.of("message", "再核对一下就诊方向。", "clientRequestId", "prompt-history"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("表达标记一。您愿意补充就诊需求吗？"));
            catalogPost(path + "/activate", admin, token, Map.of("expectedRevision", revision + 3))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveVersion").value("builtin-v1"));
            catalogPost(messages, patient, patientToken, Map.of("message", "再问一下就诊方向。", "clientRequestId", "prompt-rollback"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("内置表达。您愿意补充就诊需求吗？"));
            mvc.perform(get("/api/v1/admin/agent/tasks/" + task + "/trace").session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[?(@.type == 'MODEL_DECISION')].promptVersion").value(
                            org.hamcrest.Matchers.contains("builtin-v1", "v" + first, "v" + second, "v" + first, "builtin-v1")));
            agentSettings.saveModel(model.profileId(), model.id(), "模板试跑模型", modelUrl(), "prompt-fixture-two", null, adminId);
            var other = agentSettings.modelView().models().stream().filter(item -> item.modelName().equals("prompt-fixture-two")).findFirst().orElseThrow();
            agentSettings.testModel(other.id(), adminId);
            agentSettings.activate(other.id(), agentSettings.modelView().revision(), adminId);
            catalogPost(path + "/activate", admin, token, Map.of("id", first, "expectedRevision", revision + 4))
                    .andExpect(status().isConflict());
        } finally {
            PROMPT_RELEASE.countDown();
            heldRequest.shutdownNow();
            catalogPost(path + "/activate", admin, token, Map.of("expectedRevision", promptRevision(path, admin)))
                    .andExpect(status().isOk());
            agentSettings.activate(null, agentSettings.modelView().revision(), adminId);
            jdbc.update("DELETE FROM agent_model_revision WHERE profile_id = ?", model.profileId());
            jdbc.update("DELETE FROM agent_prompt_revision WHERE id IN (?, ?, ?)", first, second, rejected);
        }
    }

    private long savePrompt(String path, MockHttpSession admin, Csrf token, String triage, String selection) throws Exception {
        var response = catalogPost(path + "/versions", admin, token, Map.of("triage", triage, "selection", selection, "note", "试跑用例"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).at("/versions/0/id").asLong();
    }

    private long promptRevision(String path, MockHttpSession admin) throws Exception {
        var response = mvc.perform(get(path).session(admin)).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).get("revision").asLong();
    }

    private MockHttpSession signIn(String endpoint, String username, String password, MockHttpSession session,
                                   int expectedStatus) throws Exception {
        var csrf = csrf(endpoint, session);
        return (MockHttpSession) mvc.perform(post(endpoint + "/login").session(session)
                        .header(csrf.header(), csrf.token()).param("username", username).param("password", password))
                .andExpect(status().is(expectedStatus)).andReturn().getRequest().getSession(false);
    }

    @Test
    void adminCatalogPublishesSlotsAndProtectsBookingsWhenClosingOrDisabling() throws Exception {
        String catalog = "/api/v1/admin/catalog";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var adminToken = csrf(ADMIN_AUTH, admin);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientToken = csrf(PATIENT_AUTH, patient);
        var departmentData = Map.of("code", "ADMIN-SCHEDULE-FIXTURE", "name", "排班验收科室", "description", "管理端测试");
        mvc.perform(post(catalog + "/departments").session(admin).contentType("application/json")
                .content(json.writeValueAsString(departmentData))).andExpect(status().isForbidden());
        catalogPost(catalog + "/departments", patient, patientToken, departmentData).andExpect(status().isForbidden());
        catalogPost(catalog + "/departments", admin, adminToken, departmentData).andExpect(status().isCreated());
        catalogPost(catalog + "/departments", admin, adminToken, departmentData).andExpect(status().isConflict());
        var departments = mvc.perform(get(catalog + "/departments").session(admin).param("q", "ADMIN-SCHEDULE-FIXTURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        long department = json.readTree(departments.getResponse().getContentAsByteArray()).at("/items/0/id").asLong();
        catalogPut(catalog + "/departments/" + department, admin, adminToken, Map.of("name", "排班测试科室"))
                .andExpect(status().isNoContent());
        catalogPost(catalog + "/doctors", admin, adminToken, Map.of("departmentId", department, "name", "排班测试医生"))
                .andExpect(status().isCreated());
        var doctors = mvc.perform(get(catalog + "/doctors/options").session(admin).param("departmentId", "" + department))
                .andExpect(status().isOk()).andReturn();
        long doctor = json.readTree(doctors.getResponse().getContentAsByteArray()).at("/0/id").asLong();
        catalogPut(catalog + "/doctors/" + doctor, admin, adminToken, Map.of("name", "排班测试医生", "title", "测试职称"))
                .andExpect(status().isNoContent());
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4);
        var input = Map.<String, Object>of("departmentId", department, "doctorId", doctor, "date", date,
                "period", "MORNING", "start", "09:00", "minutes", 15, "count", 3);
        catalogPost(catalog + "/schedules", admin, adminToken, input).andExpect(status().isCreated());
        catalogPost(catalog + "/schedules", admin, adminToken, input).andExpect(status().isConflict());
        var invalid = new java.util.HashMap<>(input);
        invalid.put("date", date.minusDays(6));
        catalogPost(catalog + "/schedules", admin, adminToken, invalid).andExpect(status().isBadRequest());
        var schedules = mvc.perform(get(catalog + "/schedules").session(admin).param("date", date.toString())
                        .param("doctorId", "" + doctor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remaining").value(3)).andReturn();
        long schedule = json.readTree(schedules.getResponse().getContentAsByteArray()).at("/items/0/id").asLong();
        mvc.perform(get(catalog + "/schedules/" + schedule).session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].startAt").value(date + "T01:00:00Z"));
        var visible = mvc.perform(get("/api/v1/slots").session(patient).param("doctorId", "" + doctor).param("date", date.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3)).andReturn();
        long slot = json.readTree(visible.getResponse().getContentAsByteArray()).at("/slots/0/slotId").asLong();
        var created = catalogPost("/api/v1/tasks", patient, patientToken, Map.of()).andExpect(status().isCreated()).andReturn();
        String task = "/api/v1/tasks/" + json.readTree(created.getResponse().getContentAsByteArray()).get("taskId").asText();
        catalogPost(task + "/requirements", patient, patientToken,
                Map.of("doctorId", doctor, "departmentId", department, "appointmentDate", date))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.facts.candidateBatch.slots[0].slotId").value("" + slot));

        catalogPost("/api/v1/appointments", patient, patientToken, Map.of("slotId", slot, "idempotencyKey", "admin-catalog-booked"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        catalogPost(catalog + "/schedules/" + schedule + "/close", admin, adminToken, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("已有 1 笔有效预约，无法关闭此排班。"));
        for (var path : java.util.List.of("/departments/" + department, "/doctors/" + doctor)) {
            catalogPut(catalog + path + "/status", admin, adminToken, Map.of("active", false)).andExpect(status().isConflict());
        }

        adminCatalog.createSchedule(department, doctor, date.plusDays(1), AdminCatalogService.Period.AFTERNOON,
                java.time.LocalTime.of(14, 0), 15, 1);
        long closable = scheduleFor(doctor, date.plusDays(1));
        long staleSlot = firstSlot(closable);
        var nextSearch = catalogPost(task + "/requirements", patient, patientToken,
                Map.of("doctorId", doctor, "departmentId", department, "appointmentDate", date.plusDays(1)))
                .andExpect(status().isOk()).andReturn();
        String batch = json.readTree(nextSearch.getResponse().getContentAsByteArray()).at("/task/facts/candidateBatch/id").asText();
        catalogPost(task + "/selection", patient, patientToken, Map.of("batchId", batch, "slotId", staleSlot))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        var stalePlan = agentRuntime.getTask(new TaskId(task.substring(task.lastIndexOf('/') + 1)), new Principal(1001L, "PATIENT"))
                .activePlan().orElseThrow();
        catalogPost(catalog + "/schedules/" + closable + "/close", admin, adminToken, null).andExpect(status().isNoContent());
        catalogPost(catalog + "/schedules/" + closable + "/close", admin, adminToken, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/slots").session(patient).param("doctorId", "" + doctor).param("date", date.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        catalogPost("/api/v1/appointments", patient, patientToken, Map.of("slotId", staleSlot, "idempotencyKey", "admin-catalog-closed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        catalogPost(task + "/confirm", patient, patientToken, Map.of("planId", stalePlan.id().value(),
                        "planVersion", stalePlan.version(), "idempotencyKey", "admin-catalog-stale-plan"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SLOT_INVALID"));

        adminCatalog.createSchedule(department, doctor, date.plusDays(2), AdminCatalogService.Period.AFTERNOON,
                java.time.LocalTime.of(14, 0), 15, 1);
        long racing = scheduleFor(doctor, date.plusDays(2));
        var booking = AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(1001L, "PATIENT"), "admin-catalog-race", firstSlot(racing));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start = new CyclicBarrier(2);
            var closeResult = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    adminCatalog.closeSchedule(racing);
                    return true;
                } catch (AdminCatalogService.CatalogProblem refused) {
                    assertThat(refused.status()).isEqualTo(409);
                    return false;
                }
            });
            var bookingResult = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return appointmentWriter.book(booking);
            });
            boolean closed = closeResult.get(10, TimeUnit.SECONDS);
            var booked = bookingResult.get(10, TimeUnit.SECONDS);
            assertThat(booked.success()).isEqualTo(!closed);
            var result = adminCatalog.schedule(racing);
            assertThat(result.schedule().status()).isEqualTo(closed ? "CLOSED" : "OPEN");
            assertThat(result.schedule().booked()).isEqualTo(closed ? 0 : 1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void knowledgePagesVerifyPhysicalVectorsAndOnlyRetryPublishedFragments() throws Exception {
        String base = "/api/v1/admin/knowledge";
        String docNo = "RAG-OPS-FIXTURE";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var token = csrf(ADMIN_AUTH, admin);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        long archived = insertVersion(docNo, 1, "ARCHIVED");
        long published = insertVersion(docNo, 2, "PUBLISHED");
        long draft = insertVersion(docNo, 3, "DRAFT");
        try {
            insertChunk(archived, 1, 1, "INDEXED", null);
            insertChunk(published, 2, 1, "FAILED", "raw fixture-only diagnostic");
            insertChunk(draft, 3, 1, "PENDING", null);
            long publishedChunk = jdbc.queryForObject("SELECT id FROM knowledge_chunk WHERE document_id = ?", Long.class, published);
            long draftChunk = jdbc.queryForObject("SELECT id FROM knowledge_chunk WHERE document_id = ?", Long.class, draft);
            mvc.perform(get(base + "/library")).andExpect(status().isUnauthorized());
            mvc.perform(get(base + "/index/summary").session(patient)).andExpect(status().isUnauthorized());
            var overview = mvc.perform(get(base + "/library").session(admin)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(json.readTree(overview).findValuesAsText("docType")).contains("APPOINTMENT_RULE");
            int failedBefore = json.readTree(overview).findValues("failed").stream().mapToInt(node -> node.asInt()).sum();
            mvc.perform(get(base + "/documents").session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.document.docNo == '" + docNo + "')].draftVersion").value(org.hamcrest.Matchers.hasItem(3)));
            mvc.perform(get(base + "/index/summary").session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value(indexer.model())).andExpect(jsonPath("$.dimensions").value(indexer.dimensions()))
                    .andExpect(jsonPath("$.store").value("MEMORY"));
            var fragments = mvc.perform(get(base + "/chunks").session(admin).param("q", docNo)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.items[0].documentStatus").value("DRAFT"))
                    .andExpect(jsonPath("$.items[1].content").value("片段正文")).andReturn().getResponse().getContentAsString();
            assertThat(fragments).doesNotContain("raw fixture-only diagnostic");
            mvc.perform(post(base + "/chunks/" + publishedChunk + "/retry-index").session(admin)).andExpect(status().isForbidden());
            catalogPost(base + "/chunks/" + draftChunk + "/retry-index", admin, token, null).andExpect(status().isConflict());
            catalogPost(base + "/chunks/" + publishedChunk + "/retry-index", admin, token, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INDEXED"));
            var afterRetry = mvc.perform(get(base + "/library").session(admin)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(json.readTree(afterRetry).findValues("failed").stream().mapToInt(node -> node.asInt()).sum())
                    .isEqualTo(failedBefore - 1);
            mvc.perform(get(base + "/chunks").session(admin).param("q", docNo).param("status", "PUBLISHED").param("embedding", "FAILED"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalPages").value(0)).andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(get(base + "/chunks").session(admin).param("q", docNo).param("vectors", "true").param("status", "PUBLISHED"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].vectorState").value("READY"))
                    .andExpect(jsonPath("$.items[0].vectorModel").value(indexer.model()))
                    .andExpect(jsonPath("$.items[0].content").doesNotExist());
            index.removeVersion(published, 2);
            mvc.perform(get(base + "/chunks").session(admin).param("q", docNo).param("vectors", "true").param("status", "PUBLISHED"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].embeddingStatus").value("INDEXED"))
                    .andExpect(jsonPath("$.items[0].vectorState").value("MISSING"));
            catalogPost(base + "/chunks/" + publishedChunk + "/retry-index", admin, token, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INDEXED"));
            catalogPost(base + "/chunks/" + publishedChunk + "/retry-index", admin, token, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_READY"));
            var probe = catalogPost(base + "/index/probe", admin, token,
                    Map.of("docType", "APPOINTMENT_RULE", "query", "预约须知 片段正文", "topK", 10))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.indexAvailable").value(true))
                    .andReturn().getResponse().getContentAsByteArray();
            for (int number = 2; number <= 42; number++) insertChunk(draft, 3, number, "PENDING", null);
            mvc.perform(get(base + "/chunks").session(admin).param("q", docNo).param("status", "DRAFT").param("page", "3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.items.length()").value(2));
            var hits = json.readTree(probe).get("hits");
            assertThat(hits).anySatisfy(hit -> {
                assertThat(hit.get("docNo").asText()).isEqualTo(docNo);
                assertThat(hit.get("version").asInt()).isEqualTo(2);
                assertThat(hit.get("chunkNo").asInt()).isEqualTo(1);
            });
            var fixtureVersions = new java.util.ArrayList<Integer>();
            hits.forEach(hit -> { if (docNo.equals(hit.get("docNo").asText())) fixtureVersions.add(hit.get("version").asInt()); });
            assertThat(fixtureVersions).containsExactly(2);
        } finally {
            index.removeVersion(published, 2);
            jdbc.update("DELETE FROM knowledge_chunk WHERE document_id IN (?, ?, ?)", archived, published, draft);
            jdbc.update("DELETE FROM knowledge_document WHERE doc_no = ?", docNo);
        }
    }

    @Test
    void visitOperationsCountsRealPortalAndAgentBookingsAndFiltersMatchingSchedules() throws Exception {
        String dashboard = "/api/v1/admin/observability/appointments";
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientToken = csrf(PATIENT_AUTH, patient);
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        var tomorrow = date.plusDays(1);

        adminCatalog.createDepartment("OPS-STATS-FIXTURE", "运营测试科室", null);
        long department = jdbc.queryForObject("SELECT id FROM department WHERE code = 'OPS-STATS-FIXTURE'", Long.class);
        adminCatalog.createDoctor(department, "运营测试医生", null, null, null);
        long doctor = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, department);
        adminCatalog.createSchedule(department, doctor, date, AdminCatalogService.Period.MORNING,
                java.time.LocalTime.of(9, 0), 15, 2);
        long scheduleId = scheduleFor(doctor, date);
        long portalSlot = firstSlot(scheduleId);
        long agentSlot = jdbc.queryForObject("SELECT id FROM appointment_slot WHERE schedule_id = ? AND slot_no = 2", Long.class, scheduleId);
        var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
        params.set("from", date.toString());
        params.set("to", tomorrow.toString());
        params.set("departmentId", Long.toString(department));

        mvc.perform(get(dashboard).session(patient).params(params)).andExpect(status().isUnauthorized());
        mvc.perform(get(dashboard).session(admin).param("from", date.toString()).param("to", date.plusDays(90).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(dashboard).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.openSchedules").value(1))
                .andExpect(jsonPath("$.totals.booked").value(0)).andExpect(jsonPath("$.totals.remaining").value(2))
                .andExpect(jsonPath("$.trend[0].date").value(date.toString()))
                .andExpect(jsonPath("$.trend[1].date").value(tomorrow.toString()));

        var portalBooking = catalogPost("/api/v1/appointments", patient, patientToken,
                Map.of("slotId", portalSlot, "idempotencyKey", "ops-portal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        String appointmentNo = json.readTree(portalBooking.getResponse().getContentAsByteArray()).get("appointmentNo").asText();
        String register = "/api/v1/admin/appointments";
        mvc.perform(get(register).session(patient)).andExpect(status().isUnauthorized());
        mvc.perform(get(register).session(admin).param("appointmentNo", appointmentNo)
                        .param("scheduleId", Long.toString(scheduleId)).param("doctorId", Long.toString(doctor))
                        .param("departmentId", Long.toString(department)).param("from", date.toString())
                        .param("to", date.toString()).param("status", "BOOKED").param("channel", "PORTAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].appointment.appointmentNo").value(appointmentNo))
                .andExpect(jsonPath("$.items[0].appointment.status").value("BOOKED"))
                .andExpect(jsonPath("$.items[0].appointment.patientId").doesNotExist());
        mvc.perform(get(register).session(admin).param("appointmentNo", appointmentNo).param("channel", "AGENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(get("/api/v1/appointments/{number}", appointmentNo).session(patient))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BOOKED"));
        mvc.perform(get(dashboard).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.booked").value(1)).andExpect(jsonPath("$.totals.portal").value(1))
                .andExpect(jsonPath("$.totals.agent").value(0)).andExpect(jsonPath("$.totals.remaining").value(1))
                .andExpect(jsonPath("$.totals.occupied").value(1))
                .andExpect(jsonPath("$.trend[0].portal").value(1)).andExpect(jsonPath("$.trend[1].booked").value(0))
                .andExpect(jsonPath("$.departments.length()").value(1)).andExpect(jsonPath("$.departments[0].departmentId").value(department))
                .andExpect(jsonPath("$.departments[0].counts.booked").value(1)).andExpect(jsonPath("$.departments[0].counts.remaining").value(1))
                .andExpect(jsonPath("$.doctors[0].doctorId").value(doctor)).andExpect(jsonPath("$.doctors[0].counts.booked").value(1))
                .andExpect(jsonPath("$.doctors[0].counts.remaining").value(1));

        var created = catalogPost("/api/v1/tasks", patient, patientToken, Map.of()).andExpect(status().isCreated()).andReturn();
        String taskId = json.readTree(created.getResponse().getContentAsByteArray()).get("taskId").asText();
        String task = "/api/v1/tasks/" + taskId;
        var candidates = catalogPost(task + "/requirements", patient, patientToken,
                Map.of("doctorId", doctor, "departmentId", department, "appointmentDate", date))
                .andExpect(status().isOk()).andReturn();
        String batch = json.readTree(candidates.getResponse().getContentAsByteArray()).at("/task/facts/candidateBatch/id").asText();
        catalogPost(task + "/selection", patient, patientToken, Map.of("batchId", batch, "slotId", agentSlot))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"));
        var plan = agentRuntime.getTask(new TaskId(taskId), new Principal(1001L, "PATIENT")).activePlan().orElseThrow();
        catalogPost(task + "/confirm", patient, patientToken, Map.of("planId", plan.id().value(),
                        "planVersion", plan.version(), "idempotencyKey", "ops-agent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get(dashboard).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.booked").value(2)).andExpect(jsonPath("$.totals.portal").value(1))
                .andExpect(jsonPath("$.totals.agent").value(1)).andExpect(jsonPath("$.totals.remaining").value(0))
                .andExpect(jsonPath("$.totals.occupied").value(2)).andExpect(jsonPath("$.trend[0].agent").value(1));

        params.set("doctorId", Long.toString(doctor));
        String schedulePath = "/api/v1/admin/catalog/schedules";
        mvc.perform(get(schedulePath).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(scheduleId))
                .andExpect(jsonPath("$.items[0].booked").value(2)).andExpect(jsonPath("$.items[0].remaining").value(0));
        params.set("from", tomorrow.toString());
        mvc.perform(get(schedulePath).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        String agentNumber = jdbc.queryForObject("SELECT appointment_no FROM appointment WHERE slot_id = ?", String.class, agentSlot);
        catalogPost("/api/v1/appointments/" + agentNumber + "/cancel", patient, patientToken, null)
                .andExpect(status().isOk());
        var originalAgentIntent = Map.of("planId", plan.id().value(), "planVersion", plan.version(), "idempotencyKey", "ops-agent");
        for (String path : java.util.List.of(task + "/confirm", task + "/confirm/status")) {
            catalogPost(path, patient, patientToken, originalAgentIntent)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CANCELLED"));
        }
        params.set("from", date.toString());
        mvc.perform(get(dashboard).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.booked").value(1)).andExpect(jsonPath("$.totals.remaining").value(1));
        catalogPost("/api/v1/appointments/" + appointmentNo + "/cancel", patient, patientToken, null)
                .andExpect(status().isOk());
        for (String path : java.util.List.of("/api/v1/appointments", "/api/v1/appointments/status")) {
            catalogPost(path, patient, patientToken, Map.of("slotId", portalSlot, "idempotencyKey", "ops-portal"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CANCELLED"));
        }
        mvc.perform(get(dashboard).session(admin).params(params)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.booked").value(0)).andExpect(jsonPath("$.totals.remaining").value(2));
    }

    @Test
    void simultaneousPortalAndAgentConfirmationsDoNotDoubleBookAndOldRequestsCannotReuseReleasedSlot() throws Exception {
        var patientA = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientB = signIn(PATIENT_AUTH, "demo-patient-b", "Mediflow-Demo-B!2026", new MockHttpSession(), 204);
        var tokenA = csrf(PATIENT_AUTH, patientA);
        var tokenB = csrf(PATIENT_AUTH, patientB);
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4);
        long department = newClinic("LIFECYCLE-RACE");
        long doctor = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, department);
        adminCatalog.createSchedule(department, doctor, date, AdminCatalogService.Period.MORNING,
                java.time.LocalTime.of(9, 0), 15, 1);
        long slot = firstSlot(scheduleFor(doctor, date));
        String task = "/api/v1/tasks/" + json.readTree(catalogPost("/api/v1/tasks", patientB, tokenB, Map.of())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray()).get("taskId").asText();
        String batch = json.readTree(catalogPost(task + "/requirements", patientB, tokenB,
                        Map.of("doctorId", doctor, "departmentId", department, "appointmentDate", date))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .at("/task/facts/candidateBatch/id").asText();
        var selected = json.readTree(catalogPost(task + "/selection", patientB, tokenB, Map.of("batchId", batch, "slotId", slot))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var plan = selected.at("/task/activePlan");
        var agentIntent = Map.of("planId", plan.at("/id").isTextual() ? plan.at("/id").asText() : plan.at("/id/value").asText(),
                "planVersion", plan.get("version").asInt(), "idempotencyKey", "lifecycle-agent");
        var portalIntent = Map.of("slotId", slot, "idempotencyKey", "lifecycle-portal");
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        com.fasterxml.jackson.databind.JsonNode first;
        com.fasterxml.jackson.databind.JsonNode second;
        try {
            var portal = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return json.readTree(catalogPost("/api/v1/appointments", patientA, tokenA, portalIntent)
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            });
            var guide = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return json.readTree(catalogPost(task + "/confirm", patientB, tokenB, agentIntent)
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            });
            first = portal.get(15, TimeUnit.SECONDS);
            second = guide.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        boolean portalWon = first.get("success").asBoolean();
        assertThat(second.get("success").asBoolean()).isNotEqualTo(portalWon);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE slot_id = ? AND status = 'BOOKED'", Long.class, slot))
                .isEqualTo(1);
        String original = jdbc.queryForObject("SELECT appointment_no FROM appointment WHERE slot_id = ?", String.class, slot);
        var winner = portalWon ? patientA : patientB;
        var winnerToken = portalWon ? tokenA : tokenB;
        var other = portalWon ? patientB : patientA;
        var otherToken = portalWon ? tokenB : tokenA;
        String cancel = "/api/v1/appointments/" + original + "/cancel";
        catalogPost(cancel, other, otherToken, null).andExpect(status().isNotFound());
        catalogPost(cancel, winner, winnerToken, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slot))
                .isEqualTo("AVAILABLE");
        catalogPost("/api/v1/appointments", other, otherToken, Map.of("slotId", slot, "idempotencyKey", "released-slot"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        catalogPost(cancel, winner, winnerToken, null).andExpect(status().isOk());
        if (portalWon) {
            for (String endpoint : java.util.List.of("/api/v1/appointments", "/api/v1/appointments/status")) {
                catalogPost(endpoint, winner, winnerToken, portalIntent).andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("APPOINTMENT_CANCELLED"));
            }
        } else {
            for (String endpoint : java.util.List.of(task + "/confirm", task + "/confirm/status")) {
                catalogPost(endpoint, winner, winnerToken, agentIntent).andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("APPOINTMENT_CANCELLED"));
            }
        }
        assertThat(jdbc.queryForList("SELECT status FROM appointment WHERE slot_id = ? ORDER BY id", String.class, slot))
                .containsExactly("CANCELLED", "BOOKED");
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slot))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log l JOIN appointment a ON l.target_id = a.id
                WHERE a.appointment_no = ? AND l.operation_type = 'APPOINTMENT_CANCEL'
                """, Long.class, original)).isEqualTo(1);
    }

    @Test
    void concurrentReschedulesProtectTheLosingAppointmentAndCanBeAuditedFromEitherNumber() throws Exception {
        var patientA = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var patientB = signIn(PATIENT_AUTH, "demo-patient-b", "Mediflow-Demo-B!2026", new MockHttpSession(), 204);
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var tokenA = csrf(PATIENT_AUTH, patientA);
        var tokenB = csrf(PATIENT_AUTH, patientB);
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4);
        long department = newClinic("RESCHEDULE-RACE");
        long doctor = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, department);
        adminCatalog.createSchedule(department, doctor, date, AdminCatalogService.Period.MORNING,
                java.time.LocalTime.of(9, 0), 15, 3);
        var slots = jdbc.queryForList("SELECT id FROM appointment_slot WHERE schedule_id = ? ORDER BY slot_no",
                Long.class, scheduleFor(doctor, date));
        String originalA = json.readTree(catalogPost("/api/v1/appointments", patientA, tokenA,
                        Map.of("slotId", slots.get(0), "idempotencyKey", "reschedule-race-book-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("appointmentNo").asText();
        String originalB = json.readTree(catalogPost("/api/v1/appointments", patientB, tokenB,
                        Map.of("slotId", slots.get(1), "idempotencyKey", "reschedule-race-book-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("appointmentNo").asText();
        String appointments = "/api/v1/appointments/";
        String operations = "/api/v1/admin/appointments/";
        var intent = Map.of("slotId", slots.get(2), "idempotencyKey", "reschedule-race");
        catalogPost(appointments + originalA + "/reschedule", patientB, tokenB, intent).andExpect(status().isNotFound());
        catalogPost(appointments + originalA + "/reschedule/status", patientA, tokenA, intent)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        com.fasterxml.jackson.databind.JsonNode resultA;
        com.fasterxml.jackson.databind.JsonNode resultB;
        try {
            var first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return json.readTree(catalogPost(appointments + originalA + "/reschedule", patientA, tokenA, intent)
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            });
            var second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return json.readTree(catalogPost(appointments + originalB + "/reschedule", patientB, tokenB, intent)
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            });
            resultA = first.get(15, TimeUnit.SECONDS);
            resultB = second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        boolean aWon = resultA.path("success").asBoolean();
        assertThat(resultB.path("success").asBoolean()).isNotEqualTo(aWon);
        assertThat((aWon ? resultB : resultA).path("code").asText()).isEqualTo("SLOT_UNAVAILABLE");
        var winner = aWon ? patientA : patientB;
        var winnerToken = aWon ? tokenA : tokenB;
        String moved = aWon ? originalA : originalB;
        String retained = aWon ? originalB : originalA;
        String replacement = (aWon ? resultA : resultB).path("appointmentNo").asText();
        for (String path : java.util.List.of("/reschedule", "/reschedule/status")) {
            catalogPost(appointments + moved + path, winner, winnerToken, intent)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentNo").value(replacement))
                    .andExpect(jsonPath("$.outcome").value("EXISTING"));
        }
        catalogPost(appointments + moved + "/reschedule", winner, winnerToken,
                Map.of("slotId", slots.get(0), "idempotencyKey", "reschedule-race"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(get(appointments + moved).session(winner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.replacementAppointmentNo").value(replacement));
        mvc.perform(get(appointments + replacement).session(winner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAppointmentNo").value(moved));
        assertThat(jdbc.queryForList("SELECT status FROM appointment_slot WHERE schedule_id = ? ORDER BY slot_no",
                String.class, scheduleFor(doctor, date))).containsExactly(
                aWon ? "AVAILABLE" : "RESERVED", aWon ? "RESERVED" : "AVAILABLE", "RESERVED");
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE appointment_no = ?", String.class, retained))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE slot_id = ?", Long.class, slots.get(2)))
                .isEqualTo(1);
        mvc.perform(get(operations + moved + "/operations").session(winner)).andExpect(status().isUnauthorized());
        mvc.perform(get(operations + moved + "/operations").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replacementAppointmentNo").value(replacement))
                .andExpect(jsonPath("$.events.items[0].action").value("APPOINTMENT_RESCHEDULE"))
                .andExpect(jsonPath("$.events.items[0].result").value("SUCCEEDED"))
                .andExpect(jsonPath("$.events.totalCount").value(2));
        mvc.perform(get(operations + replacement + "/operations").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAppointmentNo").value(moved))
                .andExpect(jsonPath("$.events.items[0].result").value("SUCCEEDED"));
        mvc.perform(get(operations + retained + "/operations").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.items[0].result").value("REJECTED"))
                .andExpect(jsonPath("$.events.items[0].reason").value(org.hamcrest.Matchers.containsString("已被预约")));
    }

    @Test
    void rescheduleRollsBackBothSlotsAndOriginalWhenWritingSuccessAuditFails() {
        long department = newClinic("RESCHEDULE-ROLLBACK");
        long doctor = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, department);
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(5);
        adminCatalog.createSchedule(department, doctor, date, AdminCatalogService.Period.MORNING,
                java.time.LocalTime.of(9, 0), 15, 2);
        var slots = jdbc.queryForList("SELECT id FROM appointment_slot WHERE schedule_id = ? ORDER BY slot_no",
                Long.class, scheduleFor(doctor, date));
        String original = appointmentWriter.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(1001L, "PATIENT"), "reschedule-rollback-book", slots.get(0))).appointmentNo();
        var move = new AppointmentWriteService.RescheduleCommand(1001L, original, slots.get(1), "reschedule-rollback");
        jdbc.execute("""
                CREATE TRIGGER reject_test_reschedule_audit BEFORE INSERT ON operation_audit_log FOR EACH ROW
                BEGIN
                  IF NEW.operation_type = 'APPOINTMENT_RESCHEDULE' AND NEW.result_status = 'SUCCEEDED'
                      AND NEW.idempotency_key = 'reschedule-rollback' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test reschedule audit failure';
                  END IF;
                END
                """);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> appointmentWriter.reschedule(move))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_reschedule_audit");
        }
        assertThat(appointmentWriter.lookupReschedule(move)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE appointment_no = ?", String.class, original))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForList("SELECT status FROM appointment_slot WHERE schedule_id = ? ORDER BY slot_no",
                String.class, scheduleFor(doctor, date))).containsExactly("RESERVED", "AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE slot_id = ?", Long.class, slots.get(1)))
                .isZero();
        assertThat(appointmentWriter.reschedule(move).success()).isTrue();
    }

    @Test
    void checkInKeepsTheReservationAndRejectsDelayedPatientCancellation() throws Exception {
        var admin = signIn(ADMIN_AUTH, ADMIN, ADMIN_PASSWORD, new MockHttpSession(), 204);
        var adminToken = csrf(ADMIN_AUTH, admin);
        var patient = signIn(PATIENT_AUTH, "demo-patient", "Mediflow-Demo-A!2026", new MockHttpSession(), 204);
        var token = csrf(PATIENT_AUTH, patient);
        var start = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusSeconds(300);
        var date = LocalDate.ofInstant(start, ZoneId.of("Asia/Shanghai"));
        long department = newClinic("LIFECYCLE-CHECKIN");
        long doctor = jdbc.queryForObject("SELECT id FROM doctor WHERE department_id = ?", Long.class, department);
        jdbc.update("INSERT INTO doctor_schedule(doctor_id, schedule_date, clinic_period, status) VALUES (?, ?, 'MORNING', 'OPEN')", doctor, date);
        long schedule = scheduleFor(doctor, date);
        jdbc.update("INSERT INTO appointment_slot(schedule_id, slot_no, start_at, end_at, status) VALUES (?, 1, ?, ?, 'AVAILABLE')",
                schedule, java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(900)));
        long slot = firstSlot(schedule);
        var booking = catalogPost("/api/v1/appointments", patient, token, Map.of("slotId", slot, "idempotencyKey", "check-in-fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        String number = json.readTree(booking.getResponse().getContentAsByteArray()).get("appointmentNo").asText();
        long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, ADMIN);
        var transactions = beanFactory.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        for (var outside : java.util.List.of(start.minusSeconds(1801), start.plusSeconds(900))) {
            var writer = new AppointmentWriteService(jdbc.getDataSource(), transactions, java.time.Clock.fixed(outside, java.time.ZoneOffset.UTC));
            assertThat(writer.checkIn(number, adminId).code()).isEqualTo("CHECK_IN_OUTSIDE_WINDOW");
        }
        var end = start.plusSeconds(900);
        var readBeforeEnd = new AppointmentQueryService(jdbc.getDataSource(),
                java.time.Clock.fixed(end.minusMillis(1), java.time.ZoneOffset.UTC));
        var readAtEnd = new AppointmentQueryService(jdbc.getDataSource(),
                java.time.Clock.fixed(end, java.time.ZoneOffset.UTC));
        var expiredFilter = new AppointmentQueryService.AdminFilters(number, null, null, null, null,
                null, null, AppointmentQueryService.Status.EXPIRED);
        var bookedFilter = new AppointmentQueryService.AdminFilters(number, null, null, null, null,
                null, null, AppointmentQueryService.Status.BOOKED);
        assertThat(readBeforeEnd.adminAppointments(expiredFilter, 1).totalCount()).isZero();
        var before = readBeforeEnd.adminAppointments(bookedFilter, 1).items().get(0).appointment();
        assertThat(before.status()).isEqualTo("BOOKED");
        assertThat(before.canCheckIn()).isTrue();
        assertThat(readAtEnd.adminAppointments(bookedFilter, 1).totalCount()).isZero();
        var atEnd = readAtEnd.adminAppointments(expiredFilter, 1).items().get(0).appointment();
        assertThat(atEnd.status()).isEqualTo("EXPIRED");
        assertThat(atEnd.canCheckIn()).isFalse();
        assertThat(atEnd.canCancel()).isFalse();
        assertThat(readAtEnd.findForPatient(number, 1001L).orElseThrow().status()).isEqualTo("EXPIRED");

        String checkIn = "/api/v1/admin/appointments/" + number + "/check-in";
        catalogPost(checkIn, patient, csrf(ADMIN_AUTH, patient), null).andExpect(status().isUnauthorized());
        catalogPost(checkIn, admin, adminToken, null).andExpect(status().isOk());
        catalogPost(checkIn, admin, adminToken, null).andExpect(status().isOk());
        assertThat(readAtEnd.adminAppointments(expiredFilter, 1).totalCount()).isZero();
        assertThat(readAtEnd.findForPatient(number, 1001L).orElseThrow().status()).isEqualTo("CHECKED_IN");
        catalogPost("/api/v1/appointments/" + number + "/cancel", patient, token, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCEL_UNAVAILABLE"));
        mvc.perform(get("/api/v1/appointments/" + number).session(patient))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.checkedInAt").isString()).andExpect(jsonPath("$.canCancel").value(false));
        mvc.perform(get("/api/v1/admin/observability/appointments").session(admin).param("from", date.toString())
                        .param("to", date.toString()).param("departmentId", Long.toString(department)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.booked").value(1))
                .andExpect(jsonPath("$.totals.remaining").value(0));
        catalogPost("/api/v1/admin/catalog/schedules/" + schedule + "/close", admin, adminToken, null)
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM appointment_slot WHERE id = ?", String.class, slot))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_audit_log l JOIN appointment a ON l.target_id = a.id
                WHERE a.appointment_no = ? AND l.operation_type = 'APPOINTMENT_CHECK_IN'
                """, Long.class, number)).isEqualTo(1);
    }

    private long newClinic(String code) {
        adminCatalog.createDepartment(code, "预约验收科室", null);
        long department = jdbc.queryForObject("SELECT id FROM department WHERE code = ?", Long.class, code);
        adminCatalog.createDoctor(department, "预约验收医生", null, null, null);
        return department;
    }

    private long scheduleFor(long doctor, LocalDate date) {
        return jdbc.queryForObject("SELECT id FROM doctor_schedule WHERE doctor_id = ? AND schedule_date = ?", Long.class, doctor, date);
    }

    private long firstSlot(long schedule) {
        return jdbc.queryForObject("SELECT id FROM appointment_slot WHERE schedule_id = ? AND slot_no = 1", Long.class, schedule);
    }

    private ResultActions catalogPost(String path, MockHttpSession session, Csrf token, Object input) throws Exception {
        var request = post(path).session(session).header(token.header(), token.token());
        if (input != null) request.contentType("application/json").content(json.writeValueAsString(input));
        return mvc.perform(request);
    }

    private ResultActions catalogPut(String path, MockHttpSession session, Csrf token, Object input) throws Exception {
        return mvc.perform(put(path).session(session).header(token.header(), token.token())
                .contentType("application/json").content(json.writeValueAsString(input)));
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
