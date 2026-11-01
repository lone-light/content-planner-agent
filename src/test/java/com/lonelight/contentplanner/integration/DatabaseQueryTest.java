package com.lonelight.contentplanner.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lonelight.contentplanner.common.exception.BusinessException;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.service.AgentTaskService;
import com.lonelight.contentplanner.service.PlanService;
import com.lonelight.contentplanner.service.SavedPlanService;
import com.lonelight.contentplanner.service.TaskPersistenceService;
import com.lonelight.contentplanner.service.support.TaskKey;
import com.lonelight.contentplanner.support.MySqlIntegrationSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "agent.model-mode=mock")
@Import(DatabaseQueryTest.CounterConfiguration.class)
class DatabaseQueryTest extends MySqlIntegrationSupport {
    @Autowired PlanService service;
    @Autowired TaskPersistenceService store;
    @Autowired AgentTaskService tasks;
    @Autowired SavedPlanService plans;
    @Autowired DataSource dataSource;
    @Autowired JsonCodec json;
    @Autowired StatementCounter counter;

    @Test
    void schemaHasOnlyLogicalRelationsAndPointLookupsUseUniqueIndexes() throws Exception {
        var request = request();
        service.submit(request);
        var selects = counter.capture(() -> service.get(request.requestId()));
        var replay = counter.capture(() -> service.submit(request));
        List<Map<String, Object>> explain = new ArrayList<>();
        Map<String, Object> indexes = new LinkedHashMap<>();
        try (var connection = dataSource.getConnection()) {
            for (String table : List.of("agent_task", "saved_plan")) {
                try (var keys =
                        connection
                                .getMetaData()
                                .getImportedKeys(connection.getCatalog(), null, table)) {
                    assertThat(keys.next()).as("physical foreign keys in %s", table).isFalse();
                }
                var columns = indexes(connection, table);
                assertThat(columns)
                        .containsOnlyKeys(
                                "PRIMARY",
                                table.equals("agent_task")
                                        ? "uk_task_request"
                                        : "uk_saved_request");
                assertThat(columns.get("PRIMARY")).containsExactly("id");
                assertThat(
                                columns.get(
                                        table.equals("agent_task")
                                                ? "uk_task_request"
                                                : "uk_saved_request"))
                        .containsExactly("model_mode", "request_id");
                indexes.put(table, columns);
            }
            explain.add(
                    explain(
                            connection,
                            selects.get(0),
                            "PRIMARY",
                            TaskKey.of("mock", request.requestId())));
            explain.add(
                    explain(
                            connection,
                            selects.get(1),
                            "uk_saved_request",
                            "mock",
                            request.requestId()));
            String update =
                    replay.stream()
                            .filter(sql -> sql.startsWith("UPDATE"))
                            .findFirst()
                            .orElseThrow();
            explain.add(
                    explain(
                            connection,
                            update,
                            "PRIMARY",
                            "{}",
                            Timestamp.valueOf(LocalDateTime.now()),
                            TaskKey.of("mock", request.requestId())));
        }
        var path =
                Path.of(
                        Boolean.getBoolean("recordExamples")
                                ? "examples/reports/database-review.json"
                                : "target/database-review.json");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                json.write(
                        Map.of(
                                "recordTime",
                                OffsetDateTime.now(),
                                "database",
                                "MySQL 8.4.7",
                                "physicalForeignKeys",
                                0,
                                "indexes",
                                indexes,
                                "explain",
                                explain,
                                "selectsPerGet",
                                selects.size(),
                                "selectsPerReplay",
                                replay.stream().filter(sql -> sql.startsWith("SELECT")).count(),
                                "updatesPerReplay",
                                replay.stream().filter(sql -> sql.startsWith("UPDATE")).count(),
                                "associatedItemCounts",
                                List.of(1, 100))));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void readsAndReplayHaveConstantQueryCountsRegardlessOfAssociatedItems(int itemCount) {
        var request = request();
        service.submit(request);
        var state = store.find("mock", request.requestId()).orElseThrow().state();
        for (int index = 0; index < itemCount; index++) {
            state.getAssets().put("load-" + index, json.tree(Map.of("id", "load-" + index)));
        }
        store.checkpoint(state);
        var get = counter.capture(() -> service.get(request.requestId()));
        assertThat(get).hasSize(2).allMatch(sql -> sql.startsWith("SELECT"));
        var replay = counter.capture(() -> service.submit(request));
        assertThat(replay.stream().filter(sql -> sql.startsWith("SELECT"))).hasSize(2);
        assertThat(replay.stream().filter(sql -> sql.startsWith("UPDATE"))).hasSize(1);
        assertThat(replay).hasSize(3);
        assertThat(counter.capture(() -> service.trace(request.requestId()))).hasSize(1);
    }

    @Test
    void missingLogicalParentPreventsOrphanInsertInTheSameTransaction() {
        var request = request();
        var result = service.submit(request);
        var state = store.find("mock", request.requestId()).orElseThrow().state();
        assertThat(plans.removeById(result.savedPlan().savedPlanId())).isTrue();
        assertThat(tasks.removeById(TaskKey.of("mock", request.requestId()))).isTrue();
        assertThatThrownBy(() -> store.commit(state, state.getSavedPlan(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检查点目标不存在");
        assertThat(plans.findPlan("mock", request.requestId())).isEmpty();
    }

    @Test
    void logicalRelationRejectsAnotherRequestsPlanBeforeWritingAnything() {
        var request = request();
        var result = service.submit(request);
        var state = store.find("mock", request.requestId()).orElseThrow().state();
        var saved = result.savedPlan();
        var mismatched =
                new SavedPlan(
                        saved.savedPlanId(),
                        "another-request",
                        saved.plan(),
                        saved.steps(),
                        saved.script(),
                        saved.totalCostUnits(),
                        saved.estimatedFinishTime(),
                        saved.saveTime(),
                        saved.modelMode(),
                        saved.toolsProvenance());
        var statements =
                counter.capture(
                        () ->
                                assertThatThrownBy(() -> store.commit(state, mismatched, false))
                                        .isInstanceOf(BusinessException.class)
                                        .hasMessageContaining("当前任务"));
        assertThat(statements).isEmpty();
        assertThat(store.savedPlan("mock", request.requestId())).contains(saved);
    }

    private PlanRequest request() {
        return new PlanRequest(
                "db-review-" + UUID.randomUUID(),
                "字幕活动",
                BigDecimal.TEN,
                OffsetDateTime.now().plusDays(1),
                null,
                null);
    }

    private Map<String, List<String>> indexes(Connection connection, String table)
            throws Exception {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (var result =
                connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, table, true, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null)
                    indexes.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(result.getString("COLUMN_NAME"));
            }
        }
        return indexes;
    }

    private Map<String, Object> explain(
            Connection connection, String sql, String expectedIndex, Object... parameters)
            throws Exception {
        try (var statement = connection.prepareStatement("EXPLAIN " + sql)) {
            for (int index = 0; index < parameters.length; index++)
                statement.setObject(index + 1, parameters[index]);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("key")).isEqualTo(expectedIndex);
                assertThat(result.getString("type")).isIn("const", "range");
                assertThat(result.getLong("rows")).isLessThanOrEqualTo(1);
                return Map.of(
                        "sql",
                        sql,
                        "key",
                        result.getString("key"),
                        "type",
                        result.getString("type"),
                        "estimatedRows",
                        result.getLong("rows"));
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CounterConfiguration {
        @Bean
        StatementCounter statementCounter() {
            return new StatementCounter();
        }
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class})
    })
    static class StatementCounter implements Interceptor {
        private final ThreadLocal<List<String>> active = new ThreadLocal<>();

        List<String> capture(Runnable action) {
            var statements = new ArrayList<String>();
            active.set(statements);
            try {
                action.run();
                return List.copyOf(statements);
            } finally {
                active.remove();
            }
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statements = active.get();
            if (statements != null) {
                var statement = (MappedStatement) invocation.getArgs()[0];
                statements.add(
                        statement
                                .getBoundSql(invocation.getArgs()[1])
                                .getSql()
                                .replaceAll("\\s+", " ")
                                .trim());
            }
            return invocation.proceed();
        }
    }
}
