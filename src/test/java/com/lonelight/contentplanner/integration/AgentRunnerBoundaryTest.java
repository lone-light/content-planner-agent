package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lonelight.contentplanner.agent.client.DecisionContext;
import com.lonelight.contentplanner.agent.client.MockModelClient;
import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.client.ModelFailure;
import com.lonelight.contentplanner.agent.runtime.AgentRunner;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.agent.AgentAction;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.impl.PlanServiceImpl;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import com.lonelight.contentplanner.tool.PlanCalculator;
import com.lonelight.contentplanner.tool.ToolCatalog;
import com.lonelight.contentplanner.tool.ToolExecutor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"agent.model-mode=mock"})
class AgentRunnerBoundaryTest extends MySqlIntegrationSupport {
    @Autowired TaskPersistenceService store;
    @Autowired ToolExecutor tools;
    @Autowired PlanCalculator calculator;
    @Autowired ToolCatalog catalog;
    @Autowired JsonCodec json;
    @Autowired Clock clock;

    @ParameterizedTest
    @ValueSource(strings = {"15", "20"})
    void switchingPlansKeepsSpentCostsAndDoesNotBorrowTheOldScriptsStatus(String budget) {
        var client =
                model(
                        context -> {
                            if (context.step() == 1)
                                return tool(ToolName.SEARCH_ASSETS, Map.of("query", "字幕"));
                            if (context.step() == 2)
                                return tool(
                                        ToolName.ESTIMATE_PLAN,
                                        Map.of("planMode", "STANDARD", "assetIds", List.of("P01")));
                            if (context.step() == 3) {
                                var quote = context.state().getQuotes().values().iterator().next();
                                return tool(
                                        ToolName.GENERATE_SCRIPT,
                                        Map.of(
                                                "planId",
                                                quote.planId(),
                                                "quoteId",
                                                quote.quoteId()));
                            }
                            if (context.step() == 4)
                                return tool(
                                        ToolName.ESTIMATE_PLAN,
                                        Map.of("planMode", "LEAN", "assetIds", List.of("P01")));
                            return new AgentAction(
                                    AgentAction.Type.FINISH,
                                    null,
                                    null,
                                    TaskStatus.INCOMPLETE,
                                    "测试在重新报价后主动停止",
                                    List.of());
                        });
        var result = service(client, 5).submit(request(budget));
        assertThat(result.spentUnits()).isEqualByComparingTo("11");
        assertThat(result.projectedTotalUnits()).isEqualByComparingTo("18");
        assertThat(result.taskPlan())
                .filteredOn(step -> step.id().equals("script"))
                .singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("PENDING"));
        assertThat(result.steps())
                .filteredOn(
                        step ->
                                step.tool() == ToolName.GENERATE_SCRIPT
                                        && step.observation().success())
                .hasSize(1);
        assertThat(result.reasonCode()).isEqualTo("MODEL_STOPPED");
        assertThat(result.saved()).isFalse();
    }

    @Test
    void repeatedFalseCompletionIsRejectedAndBounded() {
        var service = service(model(context -> finish()), 3);
        var request = request("20");
        var result = service.submit(request);
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.reasonCode()).isEqualTo("STEP_LIMIT");
        assertThat(result.totalModelSteps()).isEqualTo(3);
        assertThat(result.saved()).isFalse();
        assertThat(service.trace(request.requestId()))
                .contains("ACTION_REJECTED", "INVALID_COMPLETION");
    }

    @Test
    void malformedActionConsumesAStepRatherThanLoopingForever() {
        var result = service(model(context -> null), 2).submit(request("20"));
        assertThat(result.reasonCode()).isEqualTo("STEP_LIMIT");
        assertThat(result.totalModelSteps()).isEqualTo(2);
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void modelConnectionFailureDoesNotSilentlyFallbackToMock() {
        var client =
                model(
                        context -> {
                            throw new ModelFailure("MODEL_TIMEOUT", "本地测试模拟模型超时", false);
                        });
        var result = service(client, 5).submit(request("20"));
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.reasonCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(result.totalModelSteps()).isEqualTo(1);
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void generationWithoutAQuoteDoesNotExecuteOrBill() {
        var client =
                model(
                        context ->
                                tool(
                                        ToolName.GENERATE_SCRIPT,
                                        Map.of("planId", "made-up", "quoteId", "made-up")));
        var result = service(client, 2).submit(request("20"));
        assertThat(result.spentUnits()).isZero();
        assertThat(result.steps())
                .allSatisfy(
                        step -> assertThat(step.observation().code()).isEqualTo("QUOTE_REQUIRED"));
        assertThat(result.saved()).isFalse();
    }

    @Test
    void saveWithoutASuccessfulScriptIsRejected() {
        var client =
                model(
                        context -> {
                            if (context.step() == 1)
                                return tool(ToolName.SEARCH_ASSETS, Map.of("query", "字幕"));
                            if (context.step() == 2)
                                return tool(
                                        ToolName.ESTIMATE_PLAN,
                                        Map.of("planMode", "LEAN", "assetIds", List.of("P01")));
                            return tool(
                                    ToolName.SAVE_PLAN,
                                    Map.of("planId", context.state().getActivePlanId()));
                        });
        var result = service(client, 3).submit(request("20"));
        assertThat(result.steps().getLast().observation().code()).isEqualTo("DEPENDENCY_NOT_READY");
        assertThat(result.spentUnits()).isEqualByComparingTo("1");
        assertThat(result.saved()).isFalse();
    }

    @Test
    void aModelCannotBypassAnOverBudgetQuote() {
        var client =
                model(
                        context -> {
                            if (context.step() == 1)
                                return tool(ToolName.SEARCH_ASSETS, Map.of("query", "字幕"));
                            if (context.step() == 2)
                                return tool(
                                        ToolName.ESTIMATE_PLAN,
                                        Map.of("planMode", "STANDARD", "assetIds", List.of("P01")));
                            var quote = context.state().getQuotes().values().iterator().next();
                            return tool(
                                    ToolName.GENERATE_SCRIPT,
                                    Map.of("planId", quote.planId(), "quoteId", quote.quoteId()));
                        });
        var result = service(client, 3).submit(request("10"));
        assertThat(result.steps().getLast().observation().code()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(result.spentUnits()).isEqualByComparingTo("1");
        assertThat(result.saved()).isFalse();
    }

    @Test
    void savingAtTheLastAllowedStepDoesNotHideTheUnfinishedModelLoop() {
        var service = service(new MockModelClient(json), 4);
        var request = request("20");
        var result = service.submit(request);
        assertThat(result.reasonCode()).isEqualTo("STEP_LIMIT");
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.saved()).isTrue();
        assertThat(result.spentUnits()).isEqualByComparingTo("12");
        var resumed = service.resume(request.requestId());
        assertThat(resumed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(resumed.spentUnits()).isEqualByComparingTo("12");
        assertThat(resumed.steps()).hasSize(result.steps().size());
    }

    private PlanService service(ModelClient client, int maxSteps) {
        var properties = new AgentProperties();
        properties.setModelMode("mock");
        properties.setMaxSteps(maxSteps);
        var runner = new AgentRunner(client, tools, calculator, store, json, properties, clock);
        return new PlanServiceImpl(
                store, runner, properties, catalog, calculator, client, json, clock);
    }

    private ModelClient model(Function<DecisionContext, AgentAction> choose) {
        return new ModelClient() {
            @Override
            public ModelReply decide(DecisionContext context) {
                return new ModelReply(
                        choose.apply(context),
                        json.tree(Map.of("realModel", false, "fixture", true)));
            }

            @Override
            public String identity() {
                return "test-action-fixture";
            }
        };
    }

    private AgentAction tool(ToolName name, Object args) {
        return new AgentAction(
                AgentAction.Type.TOOL_CALL, name, json.tree(args), null, "边界测试动作", List.of());
    }

    private AgentAction finish() {
        return new AgentAction(
                AgentAction.Type.FINISH, null, null, TaskStatus.COMPLETED, "测试提前结束", List.of());
    }

    private PlanRequest request(String budget) {
        return new PlanRequest(
                "boundary-" + UUID.randomUUID(),
                "字幕活动",
                new BigDecimal(budget),
                OffsetDateTime.parse("2030-01-01T12:00:00Z"),
                null,
                null);
    }
}
