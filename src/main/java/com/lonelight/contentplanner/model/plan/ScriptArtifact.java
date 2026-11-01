package com.lonelight.contentplanner.model.plan;

import java.util.List;

public record ScriptArtifact(
        String scriptId,
        String planId,
        String title,
        String content,
        List<String> assetIds,
        String provenance) {}
