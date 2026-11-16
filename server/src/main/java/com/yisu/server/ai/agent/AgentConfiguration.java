package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
    static OpenAiApi api(AgentProperties p) {
        if (p.getApiKey().isBlank()) throw new IllegalStateException("ZHIPU_API_KEY is required in live mode");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory=new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(p.getRequestTimeoutSeconds()));
        return OpenAiApi.builder().apiKey(p.getApiKey()).baseUrl(p.getBaseUrl())
            .completionsPath("/v4/chat/completions").embeddingsPath("/v4/embeddings")
            .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }
    @Bean ChatModel hotelChatModel(AgentProperties p, ObjectMapper json) {
        if (p.getMaxIterations()<1 || p.getMaxIterations()>8 || p.getRequestTimeoutSeconds()<1 || p.getRequestTimeoutSeconds()>60
            || p.getMaxSessions()<1 || p.getMaxSessions()>10000 || p.getSessionTtlMinutes()<1 || p.getSessionTtlMinutes()>120)
            throw new IllegalStateException("Invalid agent iteration/timeout bounds");
        return switch(p.getMode()) {
            case "live" -> OpenAiChatModel.builder().openAiApi(api(p))
                .defaultOptions(OpenAiChatOptions.builder().model(p.getChatModel()).temperature(.2)
                    .internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            case "demo" -> new DemoChatModel(json);
            case "disabled" -> prompt -> { throw new AgentException(503,"AI_DISABLED","智能咨询尚未配置，请联系管理员。"); };
            default -> throw new IllegalStateException("HOTEL_AI_MODE must be demo, live or disabled");
        };
    }
    @Bean EmbeddingModel hotelEmbeddingModel(AgentProperties p) {
        if (!"live".equals(p.getMode())) return new DemoEmbeddingModel();
        return new OpenAiEmbeddingModel(api(p), MetadataMode.NONE,
            OpenAiEmbeddingOptions.builder().model(p.getEmbeddingModel()).build(),
            RetryTemplate.builder().maxAttempts(2).fixedBackoff(300)
                .retryOn(org.springframework.ai.retry.TransientAiException.class).build());
    }
}
