package com.yisu.server.ai.service.impl;

import com.yisu.server.ai.agent.AgentService;
import com.yisu.server.ai.agent.AgentDtos.ChatRequest;
import com.yisu.server.ai.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AIServiceImpl implements AIService {
    @Autowired
    private AgentService agentService;

    @Override
    public String chat(String userMessage, boolean hotelQuery) {
        if (userMessage==null || userMessage.isBlank() || userMessage.length()>2000)
            throw new IllegalArgumentException("Invalid message");
        return agentService.chat(new ChatRequest(userMessage,null,UUID.randomUUID().toString())).answer();
    }
}
