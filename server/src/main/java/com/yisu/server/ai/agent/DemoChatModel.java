package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

/** Scripted offline model for reproducible demos. Never an estimate of GLM capability. */
public class DemoChatModel implements ChatModel {
    private final ObjectMapper json;
    public DemoChatModel(ObjectMapper json) { this.json=json; }
    @Override public ChatResponse call(Prompt prompt) {
        List<Message> messages=prompt.getInstructions();
        Message last=messages.get(messages.size()-1);
        if (last instanceof ToolResponseMessage result) {
            var summaries=new ArrayList<String>(); var ids=new ArrayList<String>();
            for (var response:result.getResponses()) {
                try {
                    var data=json.readTree(response.responseData());
                    if (data.path("ok").asBoolean()) {
                        summaries.add(data.path("data").path("summary").asText());
                        for (var source:data.path("evidence")) ids.add(source.path("id").asText());
                    } else summaries.add(data.path("data").path("message").asText());
                } catch (Exception ex) { summaries.add("查询响应不可用。"); }
            }
            return answer("演示答复：\n"+String.join("\n",summaries),ids);
        }
        String current=last.getText();
        List<String> turns=messages.stream().filter(m->m instanceof UserMessage).map(Message::getText).toList();
        String history=String.join("\n",turns);
        Integer hotel=lastHotel(history);
        boolean policy=current.matches("(?s).*(取消|退订|退房|几点|政策|早餐|设施).*");
        boolean availability=current.matches("(?s).*(房态|有房|几间|库存|入住.*\\d{4}-\\d{2}-\\d{2}).*");
        var calls=new ArrayList<AssistantMessage.ToolCall>();
        if (current.matches("(?s).*(退钱|帮我取消订单|帮我下单|支付|退款).*") && !current.contains("政策"))
            return answer("演示咨询只查询酒店资料，订单和付款请通过酒店正式渠道办理。",List.of());
        if (policy || availability || current.contains("路线") || current.contains("步行") || current.contains("详情")) {
            if (hotel==null) return answer("请提供酒店名称或编号，例如“酒店1的取消政策”。",List.of());
            if (availability) {
                var matcher=Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(current); var dates=new ArrayList<String>();
                while (matcher.find()) dates.add(matcher.group());
                if (dates.size()!=2) return answer("请提供入住和退房日期，例如2026-12-01至2026-12-03。",List.of());
                var count=Pattern.compile("([0-9]{1,3})间").matcher(current);
                int rooms=count.find()?Integer.parseInt(count.group(1)):1;
                calls.add(tool("roomAvailability",Map.of("hotelId",hotel,"checkIn",dates.get(0),"checkOut",dates.get(1),"roomCount",rooms)));
            }
            if (policy) calls.add(tool("searchPolicies",Map.of("hotelId",hotel,"query",current)));
            if (current.contains("路线") || current.contains("步行")) {
                var destination=Pattern.compile("到(.+?)(?:的路线|怎么走|步行|$)").matcher(current);
                if (!destination.find()) return answer("请提供明确的目的地，例如“酒店1到南京站的路线”。",List.of());
                calls.add(tool("walkingRoute",Map.of("hotelId",hotel,"destination",destination.group(1).trim())));
            }
            if (calls.isEmpty()) calls.add(tool("hotelDetails",Map.of("hotelId",hotel)));
        } else {
            String city=null;
            for (String turn:turns) { if (turn.contains("南京")) city="南京"; if (turn.contains("杭州")) city="杭州"; }
            if (city==null) return answer("想查哪个城市的酒店？也可以提供每晚预算。离线演示支持南京和杭州。",List.of());
            Double budget=null;
            var matcher=Pattern.compile("(?:预算|不超过|最高|以内)\\D{0,3}(\\d+)").matcher(history);
            while (matcher.find()) budget=Double.valueOf(matcher.group(1));
            var args=new LinkedHashMap<String,Object>(); args.put("city",city); args.put("maxNightlyPrice",budget);
            calls.add(tool("searchHotels",args));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }
    private Integer lastHotel(String text) {
        Integer hotel=null;
        var matcher=Pattern.compile("酒店\\s*([1-9][0-9]*)|玄武湖|城南|西湖驿站").matcher(text);
        while (matcher.find()) hotel=matcher.group(1)!=null?Integer.valueOf(matcher.group(1)):matcher.group().equals("玄武湖")?1:matcher.group().equals("城南")?2:3;
        return hotel;
    }
    private AssistantMessage.ToolCall tool(String name,Object args) {
        try { return new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,json.writeValueAsString(args)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private ChatResponse answer(String answer,List<String> ids) {
        try { return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(json.writeValueAsString(Map.of("answer",answer,"citationIds",ids))).build()))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
