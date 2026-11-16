package com.yisu.server.ai.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class AgentDtos {
    private AgentDtos() { }
    public record ChatRequest(
        @NotBlank @Size(max=2000) String message,
        @Pattern(regexp="[a-zA-Z0-9_-]{32,64}") String sessionId,
        @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{8,80}") String requestId) { }
    public record Evidence(String id, String title, String source, String excerpt, String dataKind) { }
    public record ToolEvent(String name, boolean success, String code, long elapsedMs) { }
    public record ChatResponse(String sessionId, String answer, List<Evidence> citations,
        List<ToolEvent> tools, String mode, String dataKind, boolean fallback, long elapsedMs) { }
    public record ToolResult(boolean ok, String code, Object data, List<Evidence> evidence) {
        public static ToolResult success(Object data, List<Evidence> evidence) {
            return new ToolResult(true, "OK", data, evidence);
        }
        public static ToolResult error(String code, String message) {
            return new ToolResult(false, code, Map.of("message", message), List.of());
        }
    }
}
