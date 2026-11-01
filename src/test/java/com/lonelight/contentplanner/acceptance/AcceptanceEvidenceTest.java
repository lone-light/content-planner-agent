package com.lonelight.contentplanner.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.model-mode=mock", "agent.simulation-enabled=true"})
class AcceptanceEvidenceTest extends MySqlIntegrationSupport {
    @Autowired TestRestTemplate http;
    @Autowired JsonCodec json;
    private final List<Object> summaries = new ArrayList<>();
    private final Path root =
            Path.of(
                    Boolean.getBoolean("recordExamples")
                            ? "examples/offline"
                            : "target/acceptance-evidence");

    @Test
    void normal() throws IOException {
        var response = run("normal", request("normal", 20), "response.json");
        assertThat(response.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("spentUnits").decimalValue()).isEqualByComparingTo("12");
        summary("normal", response, "A01 正常完成");
    }

    @Test
    void budgetReplan() throws IOException {
        var response = run("budget-replan", request("budget-replan", 10), "response.json");
        assertThat(response.at("/savedPlan/plan/planMode").asText()).isEqualTo("LEAN");
        assertThat(response.path("spentUnits").decimalValue()).isEqualByComparingTo("8");
        assertThat(response.path("quotes").findValues("feasible"))
                .anySatisfy(flag -> assertThat(flag.asBoolean()).isFalse());
        summary("budget-replan", response, "A04 报价 12 后改选 8");
    }

    @Test
    void missingBudget() throws IOException {
        var input = request("missing-budget", 10);
        input.remove("budgetUnits");
        var response = run("missing-budget", input, "response.json");
        assertThat(response.path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(response.path("totalModelSteps").asInt()).isZero();
        summary("missing-budget", response, "A02 缺预算");
    }

    @Test
    void missingDeadline() throws IOException {
        var input = request("missing-deadline", 10);
        input.remove("deadlineTime");
        var response = run("missing-deadline", input, "response.json");
        assertThat(response.path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(response.path("totalModelSteps").asInt()).isZero();
        summary("missing-deadline", response, "A02 缺截止时间");
    }

    @Test
    void toolFailure() throws IOException {
        var input = request("tool-failure", 10);
        input.put("simulate", Map.of("failTool", "GENERATE_SCRIPT", "failures", 1));
        var response = run("tool-failure", input, "response.json");
        assertThat(response.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("steps").findValuesAsText("code")).contains("TOOL_UNAVAILABLE");
        assertThat(response.path("spentUnits").decimalValue()).isEqualByComparingTo("8");
        summary("tool-failure", response, "A03 工具失败及成功结果保留");
    }

    @Test
    void estimateFailureIsObservedBeforeRetryAndReplanning() throws IOException {
        var input = request("estimate-failure", 10);
        input.put("simulate", Map.of("failTool", "ESTIMATE_PLAN", "failures", 1));
        var response = run("estimate-failure", input, "response.json");
        assertThat(response.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("spentUnits").decimalValue()).isEqualByComparingTo("8");
        assertThat(response.path("elapsedSimulatedMinutes").asInt()).isEqualTo(7);
        assertThat(response.at("/savedPlan/plan/planMode").asText()).isEqualTo("LEAN");
        var steps = response.path("steps");
        assertThat(response.path("quotes").size()).isEqualTo(2);
        assertThat(steps.get(1).path("tool").asText()).isEqualTo("ESTIMATE_PLAN");
        assertThat(steps.get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(steps.get(1).at("/observation/code").asText()).isEqualTo("TOOL_UNAVAILABLE");
        assertThat(steps.get(1).at("/observation/chargedUnits").decimalValue()).isZero();
        assertThat(steps.get(1).at("/observation/data").isNull()).isTrue();
        assertThat(steps.get(2).path("tool").asText()).isEqualTo("ESTIMATE_PLAN");
        assertThat(steps.get(2).path("arguments")).isEqualTo(steps.get(1).path("arguments"));
        assertThat(steps.get(2).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(steps.findValuesAsText("tool")).containsOnlyOnce("SEARCH_ASSETS");
        var events =
                Files.readAllLines(root.resolve("estimate-failure/trace.jsonl")).stream()
                        .map(line -> json.read(line, JsonNode.class))
                        .toList();
        var failure =
                events.stream()
                        .filter(
                                event ->
                                        "TOOL_UNAVAILABLE"
                                                .equals(event.at("/detail/result/code").asText()))
                        .findFirst()
                        .orElseThrow();
        var nextAction =
                events.stream()
                        .filter(
                                event ->
                                        event.path("sequence").asLong()
                                                > failure.path("sequence").asLong())
                        .filter(event -> "MODEL_ACTION".equals(event.path("type").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(nextAction.at("/detail/action/tool").asText()).isEqualTo("ESTIMATE_PLAN");
        assertThat(nextAction.at("/detail/action/reason").asText()).contains("错误", "重试");
        summary("estimate-failure", response, "A03 成本估算失败、反馈、重试及预算调整");
    }

    @Test
    void impossibleBudget() throws IOException {
        var response = run("impossible-budget", request("impossible-budget", 5), "response.json");
        assertThat(response.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(response.path("saved").asBoolean()).isFalse();
        assertThat(response.path("spentUnits").decimalValue()).isEqualByComparingTo("1");
        summary("impossible-budget", response, "A04 无可行方案");
    }

    @Test
    void repeatedRequest() throws IOException {
        var input = request("duplicate", 10);
        var first = run("duplicate", input, "response-first.json");
        var again = run("duplicate", input, "response.json");
        assertThat(again.path("replayed").asBoolean()).isTrue();
        assertThat(again.path("totalModelSteps")).isEqualTo(first.path("totalModelSteps"));
        assertThat(again.path("steps").size()).isEqualTo(first.path("steps").size());
        assertThat(again.at("/savedPlan/savedPlanId"))
                .isEqualTo(first.at("/savedPlan/savedPlanId"));
        summary("duplicate", again, "A05 重复请求不重复执行");
    }

    @Test
    void saveFailureAndResume() throws IOException {
        var input = request("save-resume", 10);
        input.put(
                "simulate",
                Map.of("failTool", "SAVE_PLAN", "failures", 2, "failAfterInsert", true));
        var failed = run("save-resume", input, "response-before-resume.json");
        assertThat(failed.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(failed.path("saved").asBoolean()).isFalse();
        Files.copy(
                root.resolve("save-resume/trace.jsonl"),
                root.resolve("save-resume/trace-before-resume.jsonl"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var response =
                http.postForEntity("/api/plans/evidence-save-resume/resume", null, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body).isNotNull();
        var resumed = body.path("data");
        write("save-resume", "response.json", body);
        trace("save-resume", "evidence-save-resume");
        assertThat(resumed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(resumed.path("spentUnits").decimalValue()).isEqualByComparingTo("8");
        summary("save-resume", resumed, "A06/A08 保存回滚与失败续跑");
    }

    @Test
    void expiredDeadline() throws IOException {
        var input = request("expired-deadline", 10);
        input.put("deadlineTime", "2000-01-01T00:00:00Z");
        var response = run("expired-deadline", input, "response.json");
        assertThat(response.path("reasonCode").asText()).isEqualTo("DEADLINE_EXPIRED");
        summary("expired-deadline", response, "A07 截止时间过期");
    }

    @Test
    void noAssets() throws IOException {
        var input = request("no-assets", 10);
        input.put("topic", "量子纠缠未知材料");
        var response = run("no-assets", input, "response.json");
        assertThat(response.path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(response.path("saved").asBoolean()).isFalse();
        summary("no-assets", response, "资料不足不虚构方案");
    }

    @Test
    void inputValidationAndHttpErrors() {
        var input = request("negative", -1);
        assertThat(http.postForEntity("/api/plans", input, JsonNode.class).getStatusCode().value())
                .isEqualTo(400);
        input = request("unknown-field", 10);
        input.put("invented", true);
        assertThat(http.postForEntity("/api/plans", input, JsonNode.class).getStatusCode().value())
                .isEqualTo(400);
        input = request("no-zone", 10);
        input.put("deadlineTime", "2030-01-01T12:00:00");
        assertThat(http.postForEntity("/api/plans", input, JsonNode.class).getStatusCode().value())
                .isEqualTo(400);
        assertThat(
                        http.getForEntity("/api/plans/does-not-exist", JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(http.getForEntity("/api/does-not-exist", JsonNode.class).getStatusCode().value())
                .isEqualTo(404);
        assertThat(http.getForEntity("/api/plans", JsonNode.class).getStatusCode().value())
                .isEqualTo(405);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(
                        http.exchange(
                                        "/api/plans",
                                        HttpMethod.POST,
                                        new HttpEntity<>("{", headers),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
    }

    private JsonNode run(String name, Map<String, Object> input, String filename)
            throws IOException {
        var response = http.postForEntity("/api/plans", input, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("data").path("modelMode").asText()).isEqualTo("mock");
        write(name, "request.json", input);
        write(name, filename, body);
        trace(name, input.get("requestId").toString());
        return body.path("data");
    }

    private void trace(String name, String id) throws IOException {
        var response = http.getForEntity("/api/plans/" + id + "/trace", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String text = response.getBody();
        assertThat(text).isNotBlank();
        long sequence = 1;
        for (String line : text.lines().toList()) {
            var event = json.read(line, JsonNode.class);
            assertThat(event.path("sequence").asLong()).isEqualTo(sequence++);
            assertThat(event.path("modelMode").asText()).isEqualTo("mock");
        }
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("trace.jsonl"), text, StandardCharsets.UTF_8);
    }

    private void write(String name, String filename, Object value) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(
                root.resolve(name).resolve(filename),
                json.write(value) + "\n",
                StandardCharsets.UTF_8);
    }

    private void summary(String name, JsonNode result, String coverage) {
        summaries.add(
                Map.of(
                        "case",
                        name,
                        "coverage",
                        coverage,
                        "status",
                        result.path("status"),
                        "spentUnits",
                        result.path("spentUnits"),
                        "saved",
                        result.path("saved"),
                        "modelMode",
                        "mock"));
    }

    @AfterAll
    void manifest() throws IOException {
        Files.createDirectories(root);
        Files.writeString(
                root.resolve("summary.json"),
                json.write(
                                Map.of(
                                        "recordTime",
                                        OffsetDateTime.now(),
                                        "modelMode",
                                        "mock",
                                        "realModelDecisionVerified",
                                        false,
                                        "source",
                                        "AcceptanceEvidenceTest: actual localhost HTTP",
                                        "cases",
                                        summaries))
                        + "\n",
                StandardCharsets.UTF_8);
    }

    private Map<String, Object> request(String name, int budget) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestId", "evidence-" + name);
        input.put("topic", "字幕活动");
        input.put("budgetUnits", budget);
        input.put("deadlineTime", "2030-01-01T12:00:00Z");
        input.put("preferredMode", "STANDARD");
        return input;
    }
}
