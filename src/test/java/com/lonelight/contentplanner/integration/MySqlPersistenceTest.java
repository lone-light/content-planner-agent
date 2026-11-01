package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lonelight.contentplanner.ContentPlannerApplication;
import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.service.AgentTaskService;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.SavedPlanService;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** MySQL persistence contract verified through MyBatis-Plus services. */
class MySqlPersistenceTest extends MySqlIntegrationSupport {
    @Test
    void savesUnicodeAndReplaysAfterApplicationRestart() throws Exception {
        var request = request("mysql-restart-", "字幕活动 🎬", null);
        String savedId;
        try (var first = application()) {
            try (var connection = first.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            }
            var result = first.getBean(PlanService.class).submit(request);
            assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(result.spentUnits()).isEqualByComparingTo("8");
            savedId = result.savedPlan().savedPlanId();
        }
        try (var restarted = application()) {
            var service = restarted.getBean(PlanService.class);
            assertThat(service.get(request.requestId()).savedPlan().savedPlanId())
                    .isEqualTo(savedId);
            var replay = service.submit(request);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.spentUnits()).isEqualByComparingTo("8");
            var tasks = restarted.getBean(AgentTaskService.class);
            var task = tasks.findTask("mock", request.requestId()).orElseThrow();
            assertThat(task.getCreateTime()).isNotNull();
            assertThat(task.getUpdateTime()).isAfterOrEqualTo(task.getCreateTime());
            var saved =
                    restarted
                            .getBean(SavedPlanService.class)
                            .findPlan("mock", request.requestId())
                            .orElseThrow();
            assertThat(saved.getSaveTime()).isNotNull();
            assertThat(tasks.findTask("mock", request.requestId()).orElseThrow().getStateJson())
                    .contains("字幕活动 🎬");
            assertThat(
                            restarted
                                    .getBean(SavedPlanService.class)
                                    .findPlan("mock", request.requestId())
                                    .stream()
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void rollsBackFailedSaveAndResumesWithoutRepeatingSuccessfulTools() {
        var request =
                request(
                        "mysql-rollback-",
                        "字幕活动",
                        new PlanRequest.Simulation(ToolName.SAVE_PLAN, 2, true));
        try (var first = application()) {
            var result = first.getBean(PlanService.class).submit(request);
            assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
            assertThat(result.saved()).isFalse();
            assertThat(result.spentUnits()).isEqualByComparingTo("7");
            assertThat(
                            first
                                    .getBean(SavedPlanService.class)
                                    .findPlan("mock", request.requestId())
                                    .stream()
                                    .count())
                    .isZero();
        }
        try (var restarted = application()) {
            var result = restarted.getBean(PlanService.class).resume(request.requestId());
            assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(result.spentUnits()).isEqualByComparingTo("8");
            assertThat(result.steps())
                    .filteredOn(step -> step.tool() == ToolName.SEARCH_ASSETS)
                    .hasSize(1);
            assertThat(result.steps())
                    .filteredOn(step -> step.tool() == ToolName.GENERATE_SCRIPT)
                    .hasSize(1);
            assertThat(result.steps())
                    .filteredOn(
                            step ->
                                    step.tool() == ToolName.SAVE_PLAN
                                            && step.status().equals("FAILED"))
                    .hasSize(2);
        }
    }

    @Test
    void preservesCaseSensitiveRequestIdsAndRejectsChangedInput() {
        var upper = request("mysql-case-A-", "字幕活动", null);
        var lower =
                new PlanRequest(
                        upper.requestId().replace("-A-", "-a-"),
                        upper.topic(),
                        upper.budgetUnits(),
                        upper.deadlineTime(),
                        null,
                        null);
        try (var context = application()) {
            var service = context.getBean(PlanService.class);
            var first = service.submit(upper);
            var second = service.submit(lower);
            assertThat(first.saved()).isTrue();
            assertThat(second.saved()).isTrue();
            assertThat(first.savedPlan().savedPlanId())
                    .isNotEqualTo(second.savedPlan().savedPlanId());
            var changed =
                    new PlanRequest(
                            upper.requestId(),
                            upper.topic(),
                            BigDecimal.valueOf(20),
                            upper.deadlineTime(),
                            null,
                            null);
            assertThatThrownBy(() -> service.submit(changed))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同一请求 ID");
        }
    }

    private PlanRequest request(String prefix, String topic, PlanRequest.Simulation simulation) {
        return new PlanRequest(
                prefix + UUID.randomUUID(),
                topic,
                BigDecimal.TEN,
                OffsetDateTime.now().plusDays(1),
                null,
                simulation);
    }

    private ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(ContentPlannerApplication.class)
                .web(WebApplicationType.NONE)
                .run(applicationArguments());
    }
}
