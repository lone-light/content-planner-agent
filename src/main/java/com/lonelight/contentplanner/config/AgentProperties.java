package com.lonelight.contentplanner.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    @Pattern(regexp = "mock|live")
    private String modelMode = "live";

    @Min(1)
    @Max(50)
    private int maxSteps = 12;

    private boolean simulationEnabled;
    private String catalogPath = "classpath:data/tool-catalog.json";
    private String assetsPath = "classpath:data/assets.json";
    @Valid private DeepSeek deepseek = new DeepSeek();

    @Data
    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-flash";
        @ToString.Exclude private String apiKey = "";

        @Min(1)
        @Max(180)
        private int timeoutSeconds = 45;
    }
}
