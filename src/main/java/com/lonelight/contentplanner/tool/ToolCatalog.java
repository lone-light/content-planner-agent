package com.lonelight.contentplanner.tool;

import com.lonelight.contentplanner.common.json.JsonCodec;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.enums.PlanMode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Getter
@Component
public class ToolCatalog {
    private final CatalogData rates;
    private final AssetData materials;
    private final String version;

    public ToolCatalog(AgentProperties properties, ResourceLoader resources, JsonCodec json)
            throws IOException {
        try (var stream = resources.getResource(properties.getCatalogPath()).getInputStream()) {
            rates =
                    json.read(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                            CatalogData.class);
        }
        try (var stream = resources.getResource(properties.getAssetsPath()).getInputStream()) {
            materials =
                    json.read(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                            AssetData.class);
        }
        validate();
        version = json.hash(List.of(rates, materials));
    }

    private void validate() {
        if (rates.search() == null
                || rates.save() == null
                || rates.generate() == null
                || materials.items() == null
                || !"SUCCESS_ONLY".equals(rates.failureBilling())
                || !"SERIAL_SIMULATED_MINUTES_PER_ATTEMPT".equals(rates.timePolicy())) {
            throw new IllegalArgumentException("素材或费用表配置缺失，或费用/时间口径不受支持");
        }
        validateRate(rates.search());
        validateRate(rates.save());
        for (PlanMode mode : PlanMode.values()) validateRate(rates.generate().get(mode));
        long uniqueIds = materials.items().stream().map(Asset::id).distinct().count();
        if (uniqueIds != materials.items().size()) throw new IllegalArgumentException("素材 ID 重复");
        for (Asset asset : materials.items()) {
            if (asset.id() == null
                    || asset.id().isBlank()
                    || asset.tags() == null
                    || asset.facts() == null
                    || asset.facts().isEmpty()) {
                throw new IllegalArgumentException("素材结构无效");
            }
        }
    }

    private void validateRate(ToolRate rate) {
        if (rate == null
                || rate.costUnits() == null
                || rate.costUnits().signum() < 0
                || rate.costUnits().scale() > 2
                || rate.durationMinutes() < 0) {
            throw new IllegalArgumentException("工具价格或耗时无效");
        }
    }

    public record ToolRate(BigDecimal costUnits, int durationMinutes) {}

    public record CatalogData(
            String version,
            String currency,
            String failureBilling,
            String timePolicy,
            ToolRate search,
            Map<PlanMode, ToolRate> generate,
            ToolRate save) {}

    public record AssetData(String version, List<Asset> items) {}

    public record Asset(
            String id, String title, List<String> tags, List<String> facts, String source) {}
}
