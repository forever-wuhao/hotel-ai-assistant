package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.yisu.server.ai.agent.AgentDtos.*;

@SpringBootTest(properties={"hotel-ai.cache-directory=target/test-knowledge", "hotel-ai.mode=demo", "hotel-ai.amap-key="})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class HotelIntegrationTest {
    @Autowired HotelTools tools;
    @Autowired AgentService agent;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    private LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private ChatResponse chat(String message,String session) {
        return agent.chat(new ChatRequest(message,session,UUID.randomUUID().toString()));
    }
    @Test void filtersBudgetCityAndPublicationWithoutBroadening() throws Exception {
        var limited=tools.search(new HotelTools.SearchHotels("南京",300.0));
        assertThat(limited.evidence()).extracting(Evidence::id).containsExactly("hotel:2");
        assertThat(tools.search(new HotelTools.SearchHotels("南京",200.0)).evidence()).isEmpty();
        assertThat(tools.search(new HotelTools.SearchHotels("南京' OR 1=1 --",null)).evidence()).isEmpty();
        assertThat(tools.details(new HotelTools.HotelId(4)).code()).isEqualTo("HOTEL_NOT_FOUND");
        assertThat(tools.search(new HotelTools.SearchHotels("南京",Double.NaN)).code()).isEqualTo("INVALID_ARGUMENT");
    }
    @Test void inventoryUsesEveryNightAndExcludesCheckout() {
        var result=tools.availability(new HotelTools.Availability(1,today().plusDays(1).toString(),today().plusDays(3).toString(),2));
        var rows=json.valueToTree(result.data()).path("rooms");
        assertThat(result.ok()).isTrue();
        assertThat(rows.get(0).path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(rows.get(0).path("totalPrice").asDouble()).isEqualTo(1596.0);
        assertThat(rows.get(1).path("status").asText()).isEqualTo("UNAVAILABLE");
        var checkoutExcluded=tools.availability(new HotelTools.Availability(1,today().plusDays(1).toString(),today().plusDays(2).toString(),1));
        assertThat(json.valueToTree(checkoutExcluded.data()).path("rooms").get(1).path("status").asText()).isEqualTo("AVAILABLE");
    }
    @Test void unknownDatesDoNotUseTotalStockOrInventPrices() {
        var result=tools.availability(new HotelTools.Availability(1,today().plusDays(30).toString(),today().plusDays(32).toString(),1));
        for(var row:json.valueToTree(result.data()).path("rooms")) {
            assertThat(row.path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(row.path("totalPrice").isNull()).isTrue();
        }
        assertThat(tools.availability(new HotelTools.Availability(1,today().toString(),today().toString(),1)).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(tools.availability(new HotelTools.Availability(1,today().minusDays(1).toString(),today().toString(),1)).code()).isEqualTo("INVALID_ARGUMENT");
    }
    @Test void policyRetrievalStaysWithinTheRequestedHotel() {
        var one=tools.policies(new HotelTools.PolicyQuery(1,"取消退订政策"));
        var two=tools.policies(new HotelTools.PolicyQuery(2,"取消退订政策"));
        assertThat(one.ok()).isTrue(); assertThat(two.ok()).isTrue();
        assertThat(one.evidence()).isNotEmpty().allSatisfy(e->assertThat(e.id()).startsWith("policy:xuanwu:"));
        assertThat(two.evidence()).isNotEmpty().allSatisfy(e->assertThat(e.id()).startsWith("policy:chengnan:"));
        assertThat(one.evidence()).allSatisfy(e->{assertThat(e.source()).isNotBlank();assertThat(e.dataKind()).isEqualTo("demo");});
    }
    @Test void mapFailsExplicitlyWithoutAKey() {
        var result=tools.route(new HotelTools.RouteQuery(1,"南京站"));
        assertThat(result.code()).isEqualTo("MAP_NOT_CONFIGURED"); assertThat(result.evidence()).isEmpty();
    }
    @Test void multiTurnChangesBudgetAndQueriesPolicyWithFreshEvidence() {
        var first=chat("推荐南京酒店，每晚预算400元",null);
        assertThat(first.citations()).extracting(Evidence::id).containsExactly("hotel:2","hotel:1");
        var next=chat("预算改为300元",first.sessionId());
        assertThat(next.citations()).extracting(Evidence::id).containsExactly("hotel:2");
        var policy=chat("酒店1的取消政策",first.sessionId());
        assertThat(policy.tools()).extracting(ToolEvent::name).containsExactly("searchPolicies");
        assertThat(policy.citations()).allSatisfy(e->assertThat(e.id()).startsWith("policy:"));
    }
    @Test void publicApiIgnoresStaleAdminTokenAndDoesNotExposeRawErrors() throws Exception {
        http.perform(post("/api/ai/agent/chat").header("Authorization","Bearer expired-admin-token").contentType("application/json")
            .content(json.writeValueAsString(new ChatRequest("南京预算300",null,"test-001"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("demo"));
        http.perform(post("/api/ai/agent/chat").contentType("application/json").content("{broken"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        http.perform(post("/api/ai/agent/chat").contentType("application/json")
            .content(json.writeValueAsString(new ChatRequest("",null,"test-001"))))
            .andExpect(status().isBadRequest());
        http.perform(post("/api/ai/agent/chat").contentType("application/json")
            .content(json.writeValueAsString(new ChatRequest("南京","a".repeat(43),"test-002"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        http.perform(get("/api/ai/agent/status")).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
    }
}
