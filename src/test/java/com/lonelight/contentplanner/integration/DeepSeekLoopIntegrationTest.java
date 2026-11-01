package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 实际 HTTP 适配器和工具循环的本地联调；规则服务不是 DeepSeek 真实模型。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent.model-mode=live",
            "agent.simulation-enabled=true",
            "agent.deepseek.api-key=local-fixture-key-not-real"
        })
class DeepSeekLoopIntegrationTest extends MySqlIntegrationSupport {
    private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
    private static final List<JsonNode> OBSERVATIONS = new CopyOnWriteArrayList<>();
    private static HttpServer provider;
    private static volatile boolean malformedFirstReply;
    private static volatile boolean failAfterScript;
    private static volatile boolean misleadingFinish;

    @Autowired TestRestTemplate http;
    @Autowired JsonCodec json;

    @DynamicPropertySource
    static void localProvider(DynamicPropertyRegistry registry) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/chat/completions",
                exchange -> {
                    try (exchange) {
                        JsonNode request = PROVIDER_JSON.readTree(exchange.getRequestBody());
                        JsonNode observation =
                                PROVIDER_JSON.readTree(request.at("/messages/1/content").asText());
                        OBSERVATIONS.add(observation);
                        if (failAfterScript && !observation.path("scripts").isEmpty()) {
                            exchange.sendResponseHeaders(503, -1);
                            return;
                        }
                        String action =
                                malformedFirstReply && OBSERVATIONS.size() == 1
                                        ? "{\"type\":\"FINISH\",\"status\":\"NEEDS_INPUT\",\"reason\":\"无效补充字段测试\",\"missingFields\":[null]}"
                                        : PROVIDER_JSON.writeValueAsString(choose(observation));
                        byte[] bytes =
                                PROVIDER_JSON.writeValueAsBytes(
                                        Map.of(
                                                "id",
                                                "local-loop-" + OBSERVATIONS.size(),
                                                "model",
                                                "local-adaptive-fixture-not-deepseek",
                                                "usage",
                                                Map.of(
                                                        "prompt_tokens",
                                                        1,
                                                        "completion_tokens",
                                                        0,
                                                        "total_tokens",
                                                        1),
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "index",
                                                                0,
                                                                "finish_reason",
                                                                "stop",
                                                                "message",
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        action)))));
                        exchange.getResponseHeaders()
                                .set("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        provider.start();
        registry.add(
                "agent.deepseek.base-url",
                () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @BeforeEach
    void resetProvider() {
        OBSERVATIONS.clear();
        malformedFirstReply = false;
        failAfterScript = false;
        misleadingFinish = false;
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) provider.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"20,STANDARD,12,5", "10,LEAN,8,6"})
    void toolResultsReachTheNextHttpDecisionAndChangeThePath(
            int budget, String mode, int cost, int decisions) throws IOException {
        var input = request(budget, Map.of());
        var result = submit(input);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.at("/savedPlan/plan/planMode").asText()).isEqualTo(mode);
        assertThat(result.path("spentUnits").asInt()).isEqualTo(cost);
        assertThat(OBSERVATIONS).hasSize(decisions);
        assertThat(OBSERVATIONS.get(1).path("assets").get(0).path("id").asText()).isEqualTo("P01");
        assertThat(OBSERVATIONS.get(2).at("/lastObservation/data/quote/feasible").asBoolean())
                .isEqualTo(budget == 20);
        assertThat(OBSERVATIONS.get(2).path("remainingBudgetUnits").decimalValue())
                .isEqualByComparingTo(Integer.toString(budget - 1));
        assertThat(OBSERVATIONS.getLast().at("/savedPlan/totalCostUnits").asInt()).isEqualTo(cost);
        assertThat(OBSERVATIONS.getLast().path("remainingBudgetUnits").decimalValue())
                .isEqualByComparingTo(Integer.toString(budget - cost));
        var beforeSave = OBSERVATIONS.get(decisions - 2);
        assertThat(beforeSave.at("/currentAssessment/remainingEstimatedUnits").decimalValue())
                .isEqualByComparingTo("1");
        assertThat(
                        beforeSave
                                .path("quoteHistory")
                                .get(beforeSave.path("quoteHistory").size() - 1)
                                .path("remainingUnits")
                                .decimalValue())
                .isEqualByComparingTo(Integer.toString(cost - 1));
        var replay = submit(input);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(OBSERVATIONS).hasSize(decisions);
        assertLocalTrace(input.get("requestId").toString());
    }

    @Test
    void misleadingCompletionTextCannotReplaceSavedFinancialFacts() throws IOException {
        misleadingFinish = true;
        var input = request(10, Map.of("failTool", "GENERATE_SCRIPT", "failures", 1));
        var result = submit(input);
        assertThat(result.path("message").asText())
                .contains("已保存", "费用 8", "预算 10")
                .doesNotContain("已发布", "费用 0");
        assertThat(submit(input).path("message")).isEqualTo(result.path("message"));
        var queried = http.getForObject("/api/plans/" + input.get("requestId"), JsonNode.class);
        assertThat(queried.at("/data/message")).isEqualTo(result.path("message"));
        assertThat(
                        http.getForObject(
                                "/api/plans/" + input.get("requestId") + "/trace", String.class))
                .contains("已发布，费用 0，所有历史尝试均成功");
        assertLocalTrace(input.get("requestId").toString());
    }

    @Test
    void misleadingIncompleteTextCannotClaimSavingOrEraseTheBudgetGap() throws IOException {
        misleadingFinish = true;
        var input = request(5, Map.of());
        var result = submit(input);
        assertThat(result.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("saved").asBoolean()).isFalse();
        assertThat(result.path("message").asText())
                .contains("尚未保存", "费用 1", "缺少 3")
                .doesNotContain("已发布", "费用 0");
        assertLocalTrace(input.get("requestId").toString());
    }

    @Test
    void toolFailureFeedbackRetainsAssetsAndDrivesRetryOverHttp() throws IOException {
        var input = request(10, Map.of("failTool", "GENERATE_SCRIPT", "failures", 1));
        var result = submit(input);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("spentUnits").asInt()).isEqualTo(8);
        assertThat(result.path("steps").findValuesAsText("tool")).containsOnlyOnce("SEARCH_ASSETS");
        assertThat(result.path("steps").findValuesAsText("status")).contains("FAILED");
        assertThat(OBSERVATIONS)
                .filteredOn(
                        item ->
                                "TOOL_UNAVAILABLE"
                                        .equals(item.at("/lastObservation/code").asText()))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.path("assets")).hasSize(1);
                            assertThat(item.path("scripts")).isEmpty();
                            assertThat(item.path("spentUnits").asInt()).isEqualTo(1);
                        });
        assertLocalTrace(input.get("requestId").toString());
    }

    @Test
    void malformedFinishFieldsAreFedBackForRepairInsteadOfCrashing() throws IOException {
        malformedFirstReply = true;
        var input = request(20, Map.of());
        var result = submit(input);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("totalModelSteps").asInt()).isEqualTo(6);
        assertThat(OBSERVATIONS.get(1).at("/lastObservation/code").asText())
                .isEqualTo("INVALID_MODEL_ACTION");
        assertLocalTrace(input.get("requestId").toString());
    }

    @Test
    void providerFailureAfterSuccessCanResumeWithoutRegeneratingOrRebilling() throws IOException {
        failAfterScript = true;
        var input = request(20, Map.of());
        var failed = submit(input);
        assertThat(failed.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(failed.path("reasonCode").asText()).isEqualTo("MODEL_HTTP_ERROR");
        assertThat(failed.path("spentUnits").asInt()).isEqualTo(11);
        assertThat(failed.path("saved").asBoolean()).isFalse();
        int previousCalls = OBSERVATIONS.size();
        failAfterScript = false;
        var response =
                http.postForEntity(
                        "/api/plans/" + input.get("requestId") + "/resume", null, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        var resumed = body.path("data");
        assertThat(resumed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(resumed.path("spentUnits").asInt()).isEqualTo(12);
        assertThat(resumed.path("steps").findValuesAsText("tool"))
                .containsOnlyOnce("GENERATE_SCRIPT");
        assertThat(OBSERVATIONS.get(previousCalls).path("scripts")).hasSize(1);
        assertThat(OBSERVATIONS.getFirst().path("resumed").asBoolean()).isFalse();
        assertThat(OBSERVATIONS.get(previousCalls).path("resumed").asBoolean()).isTrue();
        assertThat(OBSERVATIONS.get(previousCalls).path("runId").asText())
                .isNotBlank()
                .isNotEqualTo(OBSERVATIONS.getFirst().path("runId").asText());
        assertLocalTrace(input.get("requestId").toString());
    }

    private JsonNode submit(Map<String, Object> input) {
        var response = http.postForEntity("/api/plans", input, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        return body.path("data");
    }

    private Map<String, Object> request(int budget, Map<String, Object> simulation) {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("requestId", "local-loop-" + UUID.randomUUID());
        input.put("topic", "字幕活动");
        input.put("budgetUnits", budget);
        input.put("deadlineTime", "2030-01-01T12:00:00Z");
        if (!simulation.isEmpty()) input.put("simulate", simulation);
        return input;
    }

    private void assertLocalTrace(String id) throws IOException {
        var response = http.getForEntity("/api/plans/" + id + "/trace", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String trace = response.getBody();
        assertThat(trace).isNotBlank();
        assertThat(trace).doesNotContain("local-fixture-key-not-real");
        var actions =
                trace.lines()
                        .map(line -> json.read(line, JsonNode.class))
                        .filter(event -> "MODEL_ACTION".equals(event.path("type").asText()))
                        .toList();
        assertThat(actions)
                .isNotEmpty()
                .allSatisfy(
                        action -> {
                            assertThat(action.at("/detail/metadata/liveTransport").asBoolean())
                                    .isTrue();
                            assertThat(action.at("/detail/metadata/localEndpoint").asBoolean())
                                    .isTrue();
                            assertThat(action.at("/detail/metadata/returnedModel").asText())
                                    .isEqualTo("local-adaptive-fixture-not-deepseek");
                        });
        assertThat(OBSERVATIONS)
                .allSatisfy(
                        item ->
                                assertThat(item.toString())
                                        .doesNotContain(
                                                "failAfterInsert",
                                                "simulate",
                                                "local-fixture-key-not-real"));
        Path directory = Path.of("target/local-model-loop", id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("trace.jsonl"), trace, StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("observations.json"),
                json.write(
                        Map.of(
                                "source",
                                "LOCAL_HTTP_RULE_FIXTURE",
                                "realModelDecisionVerified",
                                false,
                                "observations",
                                OBSERVATIONS)),
                StandardCharsets.UTF_8);
    }

    private static Map<String, Object> choose(JsonNode observation) {
        if (observation.path("savedPlan").isObject()) return finish("COMPLETED", "本地联调：工具已保存");
        if ("TOOL_UNAVAILABLE".equals(observation.at("/lastObservation/code").asText())) {
            var attempts = observation.path("recentAttempts");
            var last = attempts.get(attempts.size() - 1);
            return tool(last.path("tool").asText(), last.path("arguments"));
        }
        if (observation.path("assets").isEmpty())
            return tool("SEARCH_ASSETS", Map.of("query", "字幕"));
        var quotes = observation.path("quoteHistory");
        if (quotes.isEmpty())
            return tool(
                    "ESTIMATE_PLAN", Map.of("planMode", "STANDARD", "assetIds", List.of("P01")));
        var quote = quotes.get(quotes.size() - 1);
        if (!quote.path("feasible").asBoolean() && quotes.size() >= 2)
            return finish("INCOMPLETE", "两种方案均不可行");
        if (!quote.path("feasible").asBoolean())
            return tool("ESTIMATE_PLAN", Map.of("planMode", "LEAN", "assetIds", List.of("P01")));
        if (observation.path("scripts").isEmpty())
            return tool(
                    "GENERATE_SCRIPT",
                    Map.of(
                            "planId",
                            quote.path("planId").asText(),
                            "quoteId",
                            quote.path("quoteId").asText()));
        return tool("SAVE_PLAN", Map.of("planId", quote.path("planId").asText()));
    }

    private static Map<String, Object> finish(String status, String reason) {
        return Map.of(
                "type",
                "FINISH",
                "status",
                status,
                "reason",
                misleadingFinish ? "已发布，费用 0，所有历史尝试均成功" : reason);
    }

    private static Map<String, Object> tool(String name, Object arguments) {
        return Map.of(
                "type",
                "TOOL_CALL",
                "tool",
                name,
                "arguments",
                arguments,
                "reason",
                "本地规则替身根据 HTTP 观察选择动作，不证明真实模型推理");
    }
}
