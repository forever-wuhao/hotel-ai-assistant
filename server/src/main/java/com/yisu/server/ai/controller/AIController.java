package com.yisu.server.ai.controller;

import com.yisu.server.ai.service.AIService;
import com.yisu.server.common.Result;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin
public class AIController {
    @Autowired
    private AIService aiService;

    @PostMapping("/chat")
    public Result<String> chat(@RequestBody AIChatRequest request) {
        try {
            String answer = aiService.chat(request.getMessage(), request.isHotelQuery());
            return Result.success(answer);
        } catch (Exception e) {
            return Result.error("咨询暂时不可用，请检查输入或稍后重试。");
        }
    }

    @Data
    private static class AIChatRequest {
        private String message;
        private boolean hotelQuery = true;
    }
}
