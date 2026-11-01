package com.lonelight.contentplanner.model.plan;

import com.lonelight.contentplanner.model.enums.PlanMode;
import java.util.List;

public record PlanCandidate(String planId, PlanMode planMode, List<String> assetIds) {}
