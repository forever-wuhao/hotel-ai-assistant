package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.*;
import org.springframework.ai.tool.function.FunctionToolCallback;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yisu.server.ai.agent.AgentDtos.ChatRequest;

class AgentReliabilityTest {
    private final ObjectMapper json=new ObjectMapper();
    private AgentProperties properties() { var p=new AgentProperties(); p.setMode("demo"); p.setDataKind("demo"); return p; }
    private HotelTools tools() {
        var mocked=mock(HotelTools.class);
        when(mocked.callbacks()).thenReturn(List.of(FunctionToolCallback.builder("lookup",(Map<String,Object> args)->
            AgentDtos.ToolResult.success(Map.of("summary","已核实"),List.of(new AgentDtos.Evidence("fact:1","资料","test","已核实","demo"))))
            .inputType(Map.class).description("read-only fixture").build()));
        return mocked;
    }
    private AgentService service(ChatModel model) { return new AgentService(model,properties(),json,tools()); }
    private org.springframework.ai.chat.model.ChatResponse answer(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }
    private org.springframework.ai.chat.model.ChatResponse calls(String name,String args) {
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1","function",name,args))).build())));
    }
    private ChatRequest request(String session,String id) { return new ChatRequest("查一下",session,id); }
    private void code(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AgentException.class,e->assertThat(e.code).isEqualTo(code));
    }
    @Test void replayIsCachedButConflictingMessageIsRejected() {
        var count=new AtomicInteger();
        var service=service(prompt->{count.incrementAndGet();return answer("{\"answer\":\"请提供城市\",\"citationIds\":[]}");});
        var response=service.chat(request(null,"request-01"));
        assertThat(service.chat(request(response.sessionId(),"request-01"))).isEqualTo(response);
        assertThat(count.get()).isEqualTo(1);
        code(()->service.chat(new ChatRequest("changed",response.sessionId(),"request-01")),"REQUEST_CONFLICT");
    }
    @Test void transientFailureRetriesOnceButAuthenticationFailureDoesNot() {
        var count=new AtomicInteger();
        var first=service(prompt->{if(count.incrementAndGet()==1)throw new TransientAiException("synthetic temporary failure");return answer("{\"answer\":\"ok\",\"citationIds\":[]}");});
        assertThat(first.chat(request(null,"request-01")).answer()).isEqualTo("ok"); assertThat(count.get()).isEqualTo(2);
        count.set(0);
        var permanent=service(prompt->{count.incrementAndGet();throw new NonTransientAiException("synthetic credential failure");});
        code(()->permanent.chat(request(null,"request-02")),"MODEL_UNAVAILABLE"); assertThat(count.get()).isEqualTo(1);
        count.set(0);
        var repeated=service(prompt->{count.incrementAndGet();throw new TransientAiException("unavailable");});
        code(()->repeated.chat(request(null,"request-03")),"MODEL_UNAVAILABLE"); assertThat(count.get()).isEqualTo(2);
    }
    @Test void refusesFabricatedCitationAndFallsBackWhenModelOmitsEvidence() {
        code(()->service(prompt->answer("{\"answer\":\"编造\",\"citationIds\":[\"fake:1\"]}")).chat(request(null,"request-01")),"INVALID_ANSWER");
        var count=new AtomicInteger();
        var fallback=service(prompt->count.incrementAndGet()==1?calls("lookup","{}"):answer("{\"answer\":\"未经引用的结论\",\"citationIds\":[]}"));
        var response=fallback.chat(request(null,"request-02"));
        assertThat(response.fallback()).isTrue(); assertThat(response.answer()).doesNotContain("未经引用");
        assertThat(response.citations()).extracting(AgentDtos.Evidence::id).containsExactly("fact:1");
    }
    @Test void unknownToolsAreNotExecutedAndLoopIsBounded() {
        var count=new AtomicInteger();
        var service=service(prompt->{count.incrementAndGet();return calls("deleteOrders","{}");});
        code(()->service.chat(request(null,"request-01")),"ITERATION_LIMIT"); assertThat(count.get()).isEqualTo(6);
        var second=new AtomicInteger();
        var inspected=service(prompt->{
            if(second.incrementAndGet()==1)return calls("deleteOrders","{}");
            var result=(ToolResponseMessage)prompt.getInstructions().get(prompt.getInstructions().size()-1);
            assertThat(result.getResponses().get(0).responseData()).contains("UNKNOWN_TOOL");
            return answer("{\"answer\":\"只读咨询无法执行订单操作\",\"citationIds\":[]}");
        });
        assertThat(inspected.chat(request(null,"request-02")).tools()).singleElement().satisfies(t->assertThat(t.success()).isFalse());
    }
    @Test void failedTurnDoesNotPolluteHistory() {
        var count=new AtomicInteger(); var seen=new ArrayList<Prompt>();
        var service=service(prompt->{seen.add(prompt);if(count.incrementAndGet()==2)throw new IllegalStateException("failed");return answer("{\"answer\":\"ok\",\"citationIds\":[]}");});
        var first=service.chat(new ChatRequest("successful",null,"request-01"));
        code(()->service.chat(new ChatRequest("failed-turn",first.sessionId(),"request-02")),"MODEL_UNAVAILABLE");
        service.chat(new ChatRequest("retry-turn",first.sessionId(),"request-03"));
        assertThat(seen.get(2).getInstructions()).filteredOn(m->m instanceof UserMessage).extracting(Message::getText)
            .containsExactly("successful","retry-turn");
    }
    @Test void concurrentRequestsForOneSessionAreRejectedWithoutDoubleExecution() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); var count=new AtomicInteger();
        var service=service(prompt->{
            if(count.incrementAndGet()>1){started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}
            return answer("{\"answer\":\"ok\",\"citationIds\":[]}");
        });
        var first=service.chat(request(null,"request-01"));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var running=executor.submit(()->service.chat(request(first.sessionId(),"request-02")));
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            code(()->service.chat(request(first.sessionId(),"request-03")),"CONVERSATION_BUSY");
            release.countDown(); assertThat(running.get(3,TimeUnit.SECONDS).answer()).isEqualTo("ok");
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void expiredSessionRequiresExplicitNewConversation() {
        var p=properties(); p.setSessionTtlMinutes(-1);
        var service=new AgentService(prompt->answer("{\"answer\":\"ok\",\"citationIds\":[]}"),p,json,tools());
        var first=service.chat(request(null,"request-01"));
        code(()->service.chat(request(first.sessionId(),"request-02")),"SESSION_EXPIRED");
    }
}
