package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.entity.SavedPlanEntity;
import com.lonelight.contentplanner.model.enums.PlanMode;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import com.lonelight.contentplanner.model.vo.PlanResultVO;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.SavedPlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"agent.model-mode=mock", "agent.simulation-enabled=true"})
class PlanServiceTest extends MySqlIntegrationSupport {
    @Autowired PlanService service;
    @Autowired TaskPersistenceService store;
    @Autowired SavedPlanService savedPlans;

    @Test
    void equivalentDecimalAndTimezoneRepresentationsReplayTheSameRequest() {
        var request = request("10.00", null);
        service.submit(request);
        var equivalent =
                new PlanRequest(
                        request.requestId(),
                        "  字幕活动  ",
                        new BigDecimal("10.0"),
                        request.deadlineTime()
                                .withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)),
                        PlanMode.STANDARD,
                        null);
        assertThat(service.submit(equivalent).replayed()).isTrue();
    }

    @Test
    void normalPlanIsActuallySavedWithReconciledCostsAndDependencies() {
        var request = request("20", null);
        var result = service.submit(request);
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.saved()).isTrue();
        assertThat(result.savedPlan().plan().planMode()).isEqualTo(PlanMode.STANDARD);
        assertThat(result.spentUnits()).isEqualByComparingTo("12");
        assertThat(result.savedPlan().totalCostUnits()).isEqualByComparingTo(result.spentUnits());
        assertThat(result.remainingEstimatedUnits()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.savedPlan().script().assetIds()).containsExactly("P01");
        assertThat(result.taskPlan())
                .allSatisfy(step -> assertThat(step.status()).isEqualTo("SUCCEEDED"));
        assertThat(store.savedPlan("mock", request.requestId())).isPresent();
        assertThat(result.steps())
                .extracting(ToolAttempt::tool)
                .containsExactly(
                        ToolName.SEARCH_ASSETS,
                        ToolName.ESTIMATE_PLAN,
                        ToolName.GENERATE_SCRIPT,
                        ToolName.SAVE_PLAN);
    }

    @Test
    void overBudgetQuoteChangesTheNextChoiceAndNeverChargesRejectedGeneration() {
        var result = service.submit(request("10", null));
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.savedPlan().plan().planMode()).isEqualTo(PlanMode.LEAN);
        assertThat(result.spentUnits()).isEqualByComparingTo("8");
        assertThat(result.quotes())
                .anySatisfy(
                        quote -> {
                            assertThat(quote.projectedTotalUnits()).isEqualByComparingTo("12");
                            assertThat(quote.feasible()).isFalse();
                            assertThat(quote.budgetGapUnits()).isEqualByComparingTo("2");
                        });
        assertThat(result.steps())
                .filteredOn(step -> step.tool() == ToolName.GENERATE_SCRIPT)
                .hasSize(1);
    }

    @Test
    void missingBudgetDoesNotInvokeAModelOrPaidTools() {
        var request = new PlanRequest(id(), "字幕活动", null, future(), null, null);
        var result = service.submit(request);
        assertThat(result.status()).isEqualTo(TaskStatus.NEEDS_INPUT);
        assertThat(result.missingFields()).containsExactly("budgetUnits");
        assertThat(result.totalModelSteps()).isZero();
        assertThat(result.steps()).isEmpty();
        assertThat(result.saved()).isFalse();
    }

    @Test
    void missingDeadlineIsNotGuessed() {
        var result =
                service.submit(
                        new PlanRequest(id(), "字幕活动", new BigDecimal("10"), null, null, null));
        assertThat(result.status()).isEqualTo(TaskStatus.NEEDS_INPUT);
        assertThat(result.missingFields()).containsExactly("deadlineTime");
        assertThat(result.spentUnits()).isZero();
    }

    @Test
    void oneToolFailureRetainsSuccessfulSearchAndOnlyBillsTheSuccessfulRetry() {
        var result =
                service.submit(
                        request(
                                "10",
                                new PlanRequest.Simulation(ToolName.GENERATE_SCRIPT, 1, false)));
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.spentUnits()).isEqualByComparingTo("8");
        assertThat(result.steps())
                .filteredOn(step -> step.tool() == ToolName.SEARCH_ASSETS)
                .hasSize(1);
        assertThat(result.steps())
                .filteredOn(step -> step.tool() == ToolName.GENERATE_SCRIPT)
                .hasSize(2);
        assertThat(result.steps())
                .filteredOn(step -> !step.observation().success())
                .singleElement()
                .satisfies(step -> assertThat(step.observation().chargedUnits()).isZero());
        assertThat(result.elapsedSimulatedMinutes()).isEqualTo(12);
    }

    @Test
    void repeatedRequestReplaysWithoutExtraToolsChargesOrPlans() {
        var request = request("10", null);
        var first = service.submit(request);
        var again = service.submit(request);
        assertThat(again.replayed()).isTrue();
        assertThat(again.steps()).hasSize(first.steps().size());
        assertThat(again.totalModelSteps()).isEqualTo(first.totalModelSteps());
        assertThat(again.spentUnits()).isEqualByComparingTo(first.spentUnits());
        assertThat(again.savedPlan().savedPlanId()).isEqualTo(first.savedPlan().savedPlanId());
        assertThat(
                        savedPlans
                                .lambdaQuery()
                                .eq(SavedPlanEntity::getRequestId, request.requestId())
                                .count())
                .isEqualTo(1);
        assertThat(service.trace(request.requestId())).contains("REPLAYED");
    }

    @Test
    void changedInputWithSameIdIsAConflictAndPreservesTheOriginal() {
        var request = request("10", null);
        service.submit(request);
        var changed =
                new PlanRequest(
                        request.requestId(),
                        request.topic(),
                        new BigDecimal("11"),
                        request.deadlineTime(),
                        null,
                        null);
        assertThatThrownBy(() -> service.submit(changed))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(409));
        assertThat(service.get(request.requestId()).budgetUnits()).isEqualByComparingTo("10");
    }

    @Test
    void postInsertFailureRollsBackPlanLedgerAndSuccessCheckpointThenResumes() {
        var request = request("10", new PlanRequest.Simulation(ToolName.SAVE_PLAN, 2, true));
        var failed = service.submit(request);
        assertThat(failed.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(failed.saved()).isFalse();
        assertThat(failed.savedPlan()).isNull();
        assertThat(failed.spentUnits()).isEqualByComparingTo("7");
        assertThat(store.savedPlan("mock", request.requestId())).isEmpty();
        assertThat(failed.steps())
                .filteredOn(step -> step.tool() == ToolName.SAVE_PLAN)
                .allSatisfy(
                        step -> {
                            assertThat(step.status()).isEqualTo("FAILED");
                            assertThat(step.observation().code()).isEqualTo("SAVE_FAILED");
                            assertThat(step.observation().chargedUnits()).isZero();
                        });
        var resumed = service.resume(request.requestId());
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(resumed.spentUnits()).isEqualByComparingTo("8");
        assertThat(resumed.steps())
                .filteredOn(step -> step.tool() == ToolName.SEARCH_ASSETS)
                .hasSize(1);
        assertThat(resumed.steps())
                .filteredOn(step -> step.tool() == ToolName.GENERATE_SCRIPT)
                .hasSize(1);
        assertThat(store.savedPlan("mock", request.requestId())).isPresent();
    }

    @Test
    void noFeasiblePlanReportsGapWithoutGeneratingOrSaving() {
        var result = service.submit(request("5", null));
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.reasonCode()).isEqualTo("NO_FEASIBLE_PLAN");
        assertThat(result.spentUnits()).isEqualByComparingTo("1");
        assertThat(result.projectedTotalUnits()).isEqualByComparingTo("8");
        assertThat(result.quotes()).allSatisfy(quote -> assertThat(quote.feasible()).isFalse());
        assertThat(result.steps())
                .noneSatisfy(
                        step ->
                                assertThat(step.tool())
                                        .isIn(ToolName.GENERATE_SCRIPT, ToolName.SAVE_PLAN));
        assertThat(result.saved()).isFalse();
    }

    @Test
    void insufficientTimeIsARealConstraintEvenWithEnoughBudget() {
        var request =
                new PlanRequest(
                        id(),
                        "字幕活动",
                        new BigDecimal("100"),
                        OffsetDateTime.now().plusMinutes(3),
                        null,
                        null);
        var result = service.submit(request);
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.quotes())
                .allSatisfy(quote -> assertThat(quote.deadlineGapMinutes()).isPositive());
        assertThat(result.saved()).isFalse();
    }

    @Test
    void expiredDeadlineStopsBeforeAnyToolOrModel() {
        var request =
                new PlanRequest(
                        id(),
                        "字幕活动",
                        BigDecimal.TEN,
                        OffsetDateTime.parse("2000-01-01T00:00:00Z"),
                        null,
                        null);
        var result = service.submit(request);
        assertThat(result.reasonCode()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.totalModelSteps()).isZero();
        assertThat(result.steps()).isEmpty();
    }

    @Test
    void noMaterialsRequestsInputInsteadOfInventingAPlan() {
        var request = new PlanRequest(id(), "量子纠缠未知材料", BigDecimal.TEN, future(), null, null);
        var result = service.submit(request);
        assertThat(result.status()).isEqualTo(TaskStatus.NEEDS_INPUT);
        assertThat(result.missingFields()).contains("assets");
        assertThat(result.saved()).isFalse();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void zeroBudgetDoesNotChargeAnUnaffordableSearch() {
        var result = service.submit(request("0", null));
        assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
        assertThat(result.spentUnits()).isZero();
        assertThat(result.reasonCode()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void concurrentDuplicatesUseTheSamePersistedResult() {
        var request = request("10", null);
        var first = CompletableFuture.supplyAsync(() -> service.submit(request));
        var second = CompletableFuture.supplyAsync(() -> service.submit(request));
        var results = List.of(first.join(), second.join());
        assertThat(results)
                .allSatisfy(result -> assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED));
        assertThat(results)
                .extracting(result -> result.savedPlan().savedPlanId())
                .containsOnly(results.getFirst().savedPlan().savedPlanId());
        assertThat(results).filteredOn(PlanResultVO::replayed).hasSize(1);
        assertThat(service.get(request.requestId()).spentUnits()).isEqualByComparingTo("8");
    }

    private PlanRequest request(String budget, PlanRequest.Simulation simulation) {
        return new PlanRequest(id(), "字幕活动", new BigDecimal(budget), future(), null, simulation);
    }

    private static String id() {
        return "case-" + UUID.randomUUID();
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.parse("2030-01-01T12:00:00Z");
    }
}
