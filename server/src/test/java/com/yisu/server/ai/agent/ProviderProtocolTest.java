package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.function.FunctionToolCallback;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real Spring AI serialization against a local fake provider, never a paid API. */
class ProviderProtocolTest {
    @Test void serializesToolsAndFeedsObservationsBackAcrossMultipleRounds() throws Exception {
        var json=new ObjectMapper(); var requests=new ArrayList<JsonNode>(); var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v4/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            int round=requests.size();
            Map<String,Object> message=round<3?
                Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","tool-"+round,"type","function",
                    "function",Map.of("name","lookup","arguments",json.writeValueAsString(Map.of("step",round)))))):
                Map.of("role","assistant","content","{\"answer\":\"根据两次查询完成核对\",\"citationIds\":[\"fact:2\"]}");
            var body=json.writeValueAsBytes(Map.of("id","response-"+round,"object","chat.completion","created",1,"model","fixture",
                "choices",List.of(Map.of("index",0,"message",message,"finish_reason",round<3?"tool_calls":"stop")),
                "usage",Map.of("prompt_tokens",12,"completion_tokens",5,"total_tokens",17)));
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v4/embeddings",exchange->{
            var request=json.readTree(exchange.getRequestBody());
            assertThat(request.path("model").asText()).isEqualTo("embedding-3");
            var body="{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}],\"model\":\"embedding-3\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var p=new AgentProperties(); p.setMode("live");p.setApiKey("local-test-key");p.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var hotel=mock(HotelTools.class);
            when(hotel.callbacks()).thenReturn(List.of(FunctionToolCallback.builder("lookup",(Map<String,Object> args)->{
                int step=calls.incrementAndGet();
                return AgentDtos.ToolResult.success(Map.of("step",step),List.of(new AgentDtos.Evidence("fact:"+step,"事实","local test","observed-"+step,"demo")));
            }).inputType(Map.class).description("read-only test lookup").build()));
            var config=new AgentConfiguration();
            var service=new AgentService(config.hotelChatModel(p,json),p,json,hotel);
            var response=service.chat(new AgentDtos.ChatRequest("请逐步查询",null,"request-01"));
            assertThat(requests).hasSize(3); assertThat(calls.get()).isEqualTo(2);
            assertThat(requests.get(0).path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
            assertThat(requests.get(1).path("messages").toString()).contains("tool-1","observed-1");
            assertThat(requests.get(2).path("messages").toString()).contains("tool-2","observed-2");
            assertThat(response.citations()).extracting(AgentDtos.Evidence::id).containsExactly("fact:2");
            assertThat(config.hotelEmbeddingModel(p).embed("policy")).hasSize(3);
        } finally { server.stop(0); }
    }
}
