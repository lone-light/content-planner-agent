package com.lonelight.contentplanner.model.dto;

import com.lonelight.contentplanner.model.enums.PlanMode;
import com.lonelight.contentplanner.model.enums.ToolName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public record PlanRequest(
        @NotBlank(message = "requestId 不能为空")
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", message = "requestId 格式无效")
                String requestId,
        @Size(max = 500, message = "主题最多 500 字符") String topic,
        @DecimalMin(value = "0", message = "预算不能为负数")
                @Digits(integer = 9, fraction = 2, message = "预算最多 9 位整数和 2 位小数")
                BigDecimal budgetUnits,
        OffsetDateTime deadlineTime,
        PlanMode preferredMode,
        @Valid Simulation simulate) {
    public PlanRequest {
        topic = topic == null ? null : topic.trim();
        budgetUnits = budgetUnits == null ? null : budgetUnits.stripTrailingZeros();
        deadlineTime =
                deadlineTime == null ? null : deadlineTime.withOffsetSameInstant(ZoneOffset.UTC);
        preferredMode = preferredMode == null ? PlanMode.STANDARD : preferredMode;
    }

    public List<String> missingFields() {
        List<String> fields = new ArrayList<>();
        if (topic == null || topic.isBlank()) fields.add("topic");
        if (budgetUnits == null) fields.add("budgetUnits");
        if (deadlineTime == null) fields.add("deadlineTime");
        return List.copyOf(fields);
    }

    public PlanMode effectivePreferredMode() {
        return preferredMode == null ? PlanMode.STANDARD : preferredMode;
    }

    public record Simulation(
            ToolName failTool, @Min(0) @Max(5) int failures, boolean failAfterInsert) {}
}
