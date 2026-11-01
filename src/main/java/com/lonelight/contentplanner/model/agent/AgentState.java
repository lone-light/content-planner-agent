package com.lonelight.contentplanner.model.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.enums.TaskStatus;
import com.lonelight.contentplanner.model.enums.ToolName;
import com.lonelight.contentplanner.model.plan.CostQuote;
import com.lonelight.contentplanner.model.plan.PlanCandidate;
import com.lonelight.contentplanner.model.plan.SavedPlan;
import com.lonelight.contentplanner.model.plan.ScriptArtifact;
import com.lonelight.contentplanner.model.tool.ToolAttempt;
import com.lonelight.contentplanner.model.tool.ToolObservation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class AgentState {
    private PlanRequest request;
    private String modelMode;
    private String modelIdentity;
    private String dataVersion;
    private TaskStatus status = TaskStatus.RUNNING;
    private String reasonCode;
    private String message;
    private List<String> missingFields = new ArrayList<>();
    private OffsetDateTime startTime;
    private int elapsedMinutes;
    private int totalModelSteps;
    private String currentRunId;
    private Map<String, JsonNode> assets = new LinkedHashMap<>();
    private Map<String, PlanCandidate> plans = new LinkedHashMap<>();
    private String activePlanId;
    private Map<String, CostQuote> quotes = new LinkedHashMap<>();
    private Map<String, ScriptArtifact> scripts = new LinkedHashMap<>();
    private Map<String, ToolObservation> cache = new LinkedHashMap<>();
    private Map<ToolName, Integer> injectedFailures = new LinkedHashMap<>();
    private List<ToolAttempt> attempts = new ArrayList<>();
    private List<TraceEvent> trace = new ArrayList<>();
    private ToolObservation lastObservation;
    private SavedPlan savedPlan;

    @JsonIgnore
    public BigDecimal spentUnits() {
        return attempts.stream()
                .map(attempt -> attempt.observation().chargedUnits())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
