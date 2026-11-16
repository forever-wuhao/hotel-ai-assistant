package com.yisu.server.ai.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hotel-ai")
public class AgentProperties {
    private String mode = "disabled";
    private String dataKind = "live";
    @lombok.ToString.Exclude
    private String apiKey = "";
    private String baseUrl = "https://open.bigmodel.cn/api/paas";
    private String chatModel = "glm-4.7";
    private String embeddingModel = "embedding-3";
    @lombok.ToString.Exclude
    private String amapKey = "";
    private String knowledgeManifest = "";
    private String cacheDirectory = "runtime/knowledge";
    private int maxIterations = 6;
    private int requestTimeoutSeconds = 20;
    private int sessionTtlMinutes = 30;
    private int maxSessions = 500;
}
