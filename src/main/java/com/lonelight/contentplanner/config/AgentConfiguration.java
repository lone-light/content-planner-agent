package com.lonelight.contentplanner.config;

import com.lonelight.contentplanner.agent.client.MockModelClient;
import com.lonelight.contentplanner.agent.client.ModelClient;
import com.lonelight.contentplanner.agent.client.SpringAiModelClient;
import com.lonelight.contentplanner.common.json.JsonCodec;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ModelClient modelClient(AgentProperties properties, JsonCodec json) {
        return "live".equals(properties.getModelMode())
                ? new SpringAiModelClient(properties.getDeepseek(), json)
                : new MockModelClient(json);
    }
}
