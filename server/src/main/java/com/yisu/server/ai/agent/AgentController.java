package com.yisu.server.ai.agent;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import static com.yisu.server.ai.agent.AgentDtos.*;

@RestController
@RequestMapping("/api/ai/agent")
public class AgentController {
    private final AgentService service;
    private final AgentProperties props;
    public AgentController(AgentService service,AgentProperties props) { this.service=service; this.props=props; }
    @GetMapping("/status") public Map<String,Object> status() {
        return Map.of("mode",props.getMode(),"dataKind",props.getDataKind(),"enabled",!"disabled".equals(props.getMode()),
            "readOnly",true,"sessionTtlMinutes",props.getSessionTtlMinutes());
    }
    @PostMapping("/chat") public ChatResponse chat(@Valid @RequestBody ChatRequest request) { return service.chat(request); }
    @RestControllerAdvice(assignableTypes=AgentController.class)
    public static class Errors {
        @ExceptionHandler(AgentException.class) public ResponseEntity<?> agent(AgentException ex) {
            return ResponseEntity.status(ex.status).body(Map.of("code",ex.code,"message",ex.getMessage()));
        }
        @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) public ResponseEntity<?> invalid() {
            return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","请输入有效的咨询内容，最多2000字。"));
        }
        @ExceptionHandler(Exception.class) public ResponseEntity<?> error() {
            return ResponseEntity.status(503).body(Map.of("code","SERVICE_UNAVAILABLE","message","咨询服务暂时不可用，请稍后重试。"));
        }
    }
}
