package com.lonelight.contentplanner.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.runtime.AgentRunner;
import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.model.agent.AgentState;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.tool.ToolObservation;
import com.lonelight.contentplanner.service.support.PlanResultMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DirectoryStructureTest {
    @Test
    void javaPackageDeclarationsMatchTheirSourceDirectories() throws Exception {
        var declaration = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
        for (String source : List.of("src/main/java", "src/test/java")) {
            Path root = Path.of(source);
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    var match = declaration.matcher(Files.readString(file));
                    assertThat(match.find()).as(file.toString()).isTrue();
                    assertThat(file.getParent())
                            .as(file.toString())
                            .isEqualTo(root.resolve(match.group(1).replace('.', '/')));
                }
            }
        }
    }

    @Test
    void runtimeClientsAndModelsHaveDedicatedResponsibilityPackages() {
        Map<Class<?>, String> responsibilities =
                Map.of(
                        AgentRunner.class, "agent.runtime",
                        ModelClient.class, "agent.client",
                        AgentState.class, "model.agent",
                        SavedPlan.class, "model.plan",
                        ToolObservation.class, "model.tool",
                        TaskStatus.class, "model.enums",
                        JsonCodec.class, "common.json",
                        PlanResultMessage.class, "service.support");
        responsibilities.forEach(
                (type, location) ->
                        assertThat(type.getPackageName())
                                .as(type.getSimpleName())
                                .isEqualTo("com.lonelight.contentplanner." + location));
    }
}
