package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lonelight.contentplanner.agent.client.MockModelClient;
import com.lonelight.contentplanner.agent.runtime.AgentRunner;
import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.PlanMode;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.impl.PlanServiceImpl;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import com.lonelight.contentplanner.tool.PlanCalculator;
import com.lonelight.contentplanner.tool.ToolCatalog;
import com.lonelight.contentplanner.tool.ToolExecutor;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;

@SpringBootTest(properties = {"agent.model-mode=mock"})
class CatalogVariationTest extends MySqlIntegrationSupport {
    @Autowired PlanService original;
    @Autowired TaskPersistenceService store;
    @Autowired ToolCatalog originalCatalog;
    @Autowired JsonCodec json;
    @Autowired Validator validator;
    @Autowired Clock clock;

    @Test
    void replacingPricesChangesTheChosenModeWithoutChangingAgentCode(@TempDir Path directory)
            throws IOException {
        var first = original.submit(request("10"));
        assertThat(first.savedPlan().plan().planMode()).isEqualTo(PlanMode.LEAN);
        var changed = service(directory, "1", "6", "10", "1").submit(request("10"));
        assertThat(changed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(changed.savedPlan().plan().planMode()).isEqualTo(PlanMode.STANDARD);
        assertThat(changed.spentUnits()).isEqualByComparingTo("8");
    }

    @Test
    void decimalCostsAreSummedExactly(@TempDir Path directory) throws IOException {
        var result = service(directory, "0.10", "0.20", "0.10", "0.00").submit(request("0.30"));
        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.spentUnits()).isEqualByComparingTo("0.30");
        assertThat(result.savedPlan().totalCostUnits()).isEqualByComparingTo("0.30");
    }

    @Test
    void changedDataVersionCannotSilentlyReplayAnOldCompletedTask(@TempDir Path directory)
            throws IOException {
        var request = request("10");
        original.submit(request);
        var changed = service(directory, "1", "6", "10", "1");
        assertThatThrownBy(() -> changed.submit(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void mockAndLiveNamespacesCannotReadEachOthersTasks() {
        var request = request("10");
        original.submit(request);
        assertThat(store.find("live", request.requestId())).isEmpty();
        assertThat(store.savedPlan("live", request.requestId())).isEmpty();
        assertThat(store.find("mock", request.requestId())).isPresent();
    }

    private PlanService service(
            Path directory, String search, String standard, String lean, String save)
            throws IOException {
        var baseline = originalCatalog.getRates();
        var data =
                new ToolCatalog.CatalogData(
                        "test-variation",
                        baseline.currency(),
                        baseline.failureBilling(),
                        baseline.timePolicy(),
                        new ToolCatalog.ToolRate(
                                new BigDecimal(search), baseline.search().durationMinutes()),
                        Map.of(
                                PlanMode.STANDARD,
                                new ToolCatalog.ToolRate(new BigDecimal(standard), 10),
                                PlanMode.LEAN,
                                new ToolCatalog.ToolRate(new BigDecimal(lean), 5)),
                        new ToolCatalog.ToolRate(
                                new BigDecimal(save), baseline.save().durationMinutes()));
        Path file = directory.resolve("tool-catalog.json");
        Files.writeString(file, json.write(data));
        var properties = new AgentProperties();
        properties.setModelMode("mock");
        properties.setCatalogPath(file.toUri().toString());
        var catalog = new ToolCatalog(properties, new DefaultResourceLoader(), json);
        var calculator = new PlanCalculator(catalog, json, clock);
        var tools = new ToolExecutor(catalog, calculator, json, validator, clock);
        var model = new MockModelClient(json);
        var runner = new AgentRunner(model, tools, calculator, store, json, properties, clock);
        return new PlanServiceImpl(
                store, runner, properties, catalog, calculator, model, json, clock);
    }

    private PlanRequest request(String budget) {
        return new PlanRequest(
                "catalog-" + UUID.randomUUID(),
                "字幕活动",
                new BigDecimal(budget),
                OffsetDateTime.parse("2030-01-01T12:00:00Z"),
                null,
                null);
    }
}
