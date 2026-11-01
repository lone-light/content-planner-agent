package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lonelight.contentplanner.agent.client.DecisionContext;
import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.client.ModelFailure;
import com.lonelight.contentplanner.agent.client.SpringAiModelClient;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentConfiguration;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeepSeekProtocolTest {
    private HttpServer server;
    private final JsonCodec json =
            new JsonCodec(
                    new ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    private final AtomicReference<String> captured = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int responseStatus = 200;
    private String responseBody;
    private long delayMs;
    private AgentProperties.DeepSeek settings;

    @BeforeEach
    void startLocalProvider() throws IOException {
        responseBody =
                completion(
                        "{\"type\":\"TOOL_CALL\",\"tool\":\"SEARCH_ASSETS\",\"arguments\":{\"query\":\"字幕\"},\"reason\":\"查找相关素材\"}",
                        "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    try (exchange) {
                        if (delayMs > 0) Thread.sleep(delayMs);
                        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(responseStatus, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        settings = new AgentProperties.DeepSeek();
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setApiKey("test-key-not-real");
    }

    @AfterEach
    void stopLocalProvider() {
        server.stop(0);
    }

    @Test
    void defaultsUseTheRealAdapterAndFailExplicitlyWithoutCredentials() {
        var properties = new AgentProperties();
        assertThat(properties.getModelMode()).isEqualTo("live");
        assertThat(properties.isSimulationEnabled()).isFalse();
        new ApplicationContextRunner()
                .withBean(AgentProperties.class, () -> properties)
                .withBean(JsonCodec.class, () -> json)
                .withUserConfiguration(AgentConfiguration.class)
                .run(
                        application -> {
                            var model = application.getBean(ModelClient.class);
                            assertThat(model).isInstanceOf(SpringAiModelClient.class);
                            assertThatThrownBy(() -> model.decide(context()))
                                    .isInstanceOfSatisfying(
                                            ModelFailure.class,
                                            error -> {
                                                assertThat(error.getCode())
                                                        .isEqualTo("MODEL_NOT_CONFIGURED");
                                                assertThat(error.isRecoverable()).isFalse();
                                            });
                        });
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendsTheDocumentedJsonProtocolAndExtractsOnlyTheFinalContent() {
        var reply = client().decide(context());
        JsonNode body = json.read(captured.get(), JsonNode.class);
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(authorization.get()).isEqualTo("Bearer test-key-not-real");
        assertThat(reply.action().tool()).isEqualTo(ToolName.SEARCH_ASSETS);
        assertThat(reply.metadata().path("returnedModel").asText())
                .isEqualTo("local-protocol-fixture");
        assertThat(reply.metadata().path("localEndpoint").asBoolean()).isTrue();
        assertThat(reply.metadata().has("realModel")).isFalse();
        assertThat(reply.metadata().toString())
                .doesNotContain("DO_NOT_RECORD", "test-key-not-real");
        assertThat(captured.get()).doesNotContain("failAfterInsert", "failures", "simulate");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void missingKeyFailsBeforeNetworkAndDoesNotFallback() {
        settings.setApiKey("");
        assertFailure("MODEL_NOT_CONFIGURED", false);
        assertThat(calls.get()).isZero();
    }

    @Test
    void invalidKeyCharactersCannotReachHeadersOrLogs() {
        settings.setApiKey("private\nkey");
        assertFailure("MODEL_NOT_CONFIGURED", false);
        assertThat(calls.get()).isZero();
    }

    @Test
    void providerHttpErrorIsExplicitWithoutLeakingTheResponseBody() {
        responseStatus = 401;
        responseBody = "test-key-not-real";
        assertThatThrownBy(() -> client().decide(context()))
                .isInstanceOfSatisfying(
                        ModelFailure.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo("MODEL_HTTP_ERROR");
                            assertThat(error.getMessage())
                                    .contains("401")
                                    .doesNotContain("test-key-not-real");
                        });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void malformedActionReturnsARepairableObservation() {
        responseBody = completion("not-json", "stop");
        assertFailure("INVALID_MODEL_ACTION", true);
    }

    @Test
    void emptyCompletionReturnsAnExplicitRepairableResponseError() {
        responseBody =
                json.write(
                        Map.of(
                                "id",
                                "empty-response",
                                "model",
                                "local-protocol-fixture",
                                "choices",
                                java.util.List.of()));
        assertFailure("INVALID_MODEL_RESPONSE", true);
    }

    @Test
    void unknownActionFieldsAreRejected() {
        responseBody =
                completion(
                        "{\"type\":\"FINISH\",\"status\":\"COMPLETED\",\"reason\":\"done\",\"invented\":true}",
                        "stop");
        assertFailure("INVALID_MODEL_ACTION", true);
    }

    @Test
    void truncatedOutputIsNotExecutedAsACompleteAction() {
        responseBody = completion("{", "length");
        assertFailure("MODEL_OUTPUT_TRUNCATED", true);
    }

    @Test
    void timeoutIsBoundedAndExplicit() {
        settings.setTimeoutSeconds(1);
        delayMs = 1500;
        assertFailure("MODEL_TIMEOUT", false);
    }

    @Test
    void remotePlainHttpAndCredentialsInUrlsAreRejected() {
        settings.setBaseUrl("http://example.com");
        assertThatThrownBy(this::client).isInstanceOf(IllegalArgumentException.class);
        settings.setBaseUrl("https://user:private@example.com");
        assertThatThrownBy(this::client).isInstanceOf(IllegalArgumentException.class);
    }

    private SpringAiModelClient client() {
        return new SpringAiModelClient(settings, json);
    }

    private void assertFailure(String code, boolean repairable) {
        assertThatThrownBy(() -> client().decide(context()))
                .isInstanceOfSatisfying(
                        ModelFailure.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo(code);
                            assertThat(error.isRecoverable()).isEqualTo(repairable);
                        });
    }

    private DecisionContext context() {
        AgentState state = new AgentState();
        state.setRequest(
                new PlanRequest(
                        "protocol",
                        "字幕活动",
                        BigDecimal.TEN,
                        OffsetDateTime.parse("2030-01-01T12:00:00Z"),
                        null,
                        new PlanRequest.Simulation(ToolName.SAVE_PLAN, 1, true)));
        state.setStartTime(OffsetDateTime.now());
        return new DecisionContext(state, 1, 12, OffsetDateTime.now(), false, null);
    }

    private String completion(String content, String finishReason) {
        return json.write(
                Map.of(
                        "id",
                        "local-fixture",
                        "model",
                        "local-protocol-fixture",
                        "usage",
                        Map.of("prompt_tokens", 8, "completion_tokens", 4, "total_tokens", 12),
                        "choices",
                        java.util.List.of(
                                Map.of(
                                        "index",
                                        0,
                                        "finish_reason",
                                        finishReason,
                                        "message",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                content,
                                                "reasoning_content",
                                                "DO_NOT_RECORD")))));
    }
}
