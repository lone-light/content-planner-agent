package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lonelight.contentplanner.ContentPlannerApplication;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class PersistenceRestartTest extends MySqlIntegrationSupport {
    @Test
    void mysqlSurvivesApplicationRestartAndResumesOnlyTheFailedStep() {
        var request =
                new PlanRequest(
                        "restart-" + java.util.UUID.randomUUID(),
                        "字幕活动",
                        BigDecimal.TEN,
                        OffsetDateTime.parse("2030-01-01T12:00:00Z"),
                        null,
                        new PlanRequest.Simulation(ToolName.SAVE_PLAN, 2, true));
        try (var first = application()) {
            var result = first.getBean(PlanService.class).submit(request);
            assertThat(result.status()).isEqualTo(TaskStatus.INCOMPLETE);
            assertThat(result.saved()).isFalse();
            assertThat(result.spentUnits()).isEqualByComparingTo("7");
        }
        try (var restarted = application()) {
            var service = restarted.getBean(PlanService.class);
            assertThat(service.get(request.requestId()).spentUnits()).isEqualByComparingTo("7");
            var result = service.resume(request.requestId());
            assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(result.spentUnits()).isEqualByComparingTo("8");
            assertThat(result.steps())
                    .filteredOn(step -> step.tool() == ToolName.SEARCH_ASSETS)
                    .hasSize(1);
            assertThat(result.steps())
                    .filteredOn(step -> step.tool() == ToolName.GENERATE_SCRIPT)
                    .hasSize(1);
            assertThat(service.submit(request).replayed()).isTrue();
        }
    }

    private ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(ContentPlannerApplication.class)
                .web(WebApplicationType.NONE)
                .run(applicationArguments());
    }
}
