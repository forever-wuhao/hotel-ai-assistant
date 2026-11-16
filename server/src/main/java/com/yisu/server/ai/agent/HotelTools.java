package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yisu.server.ai.agent.AgentDtos.*;

@Component
public class HotelTools {
    public record SearchHotels(String city, Double maxNightlyPrice) { }
    public record HotelId(Integer hotelId) { }
    public record Availability(Integer hotelId, String checkIn, String checkOut, Integer roomCount) { }
    public record PolicyQuery(Integer hotelId, String query) { }
    public record RouteQuery(Integer hotelId, String destination) { }
    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledge;
    private final AgentProperties props;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HotelTools(JdbcTemplate jdbc, KnowledgeService knowledge, AgentProperties props, ObjectMapper json) {
        this.jdbc=jdbc; this.knowledge=knowledge; this.props=props; this.json=json;
    }
    public List<ToolCallback> callbacks() {
        return List.of(
            FunctionToolCallback.builder("searchHotels",(SearchHotels args)->search(args)).inputType(SearchHotels.class)
                .description("按城市和每晚预算上限查询已发布酒店。city必填，maxNightlyPrice可为null。返回最多10家，不自动放宽预算。").build(),
            FunctionToolCallback.builder("hotelDetails",(HotelId args)->details(args)).inputType(HotelId.class)
                .description("查询已发布酒店的详细设施和房型资料，不推断日期库存。").build(),
            FunctionToolCallback.builder("roomAvailability",(Availability args)->availability(args)).inputType(Availability.class)
                .description("按酒店、入住日期、退房日期和房间数核对每一天的房态与总价。日期YYYY-MM-DD，退房日不计住宿。缺失日期返回UNKNOWN，不使用总库存替代。").build(),
            FunctionToolCallback.builder("searchPolicies",(PolicyQuery args)->policies(args)).inputType(PolicyQuery.class)
                .description("按指定酒店检索入住、退订和设施政策，返回可引用的政策片段；不得跨酒店套用规则。").build(),
            FunctionToolCallback.builder("walkingRoute",(RouteQuery args)->route(args)).inputType(RouteQuery.class)
                .description("从指定酒店到目的地的高德步行路线。目的地须明确，返回距离、时长；未配置或无法定位时不能编造路线。").build()
        );
    }
    private Map<String,Object> hotel(Integer id) {
        if (id==null || id<=0) return null;
        var rows=jdbc.queryForList("SELECT id,name,city,address,latitude,longitude,star_rating,min_price,facilities,description FROM hotel WHERE status=1 AND id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Evidence evidence(String id,String title,String text) {
        return new Evidence(id,title,"酒店数据库查询快照",text,props.getDataKind());
    }
    public ToolResult search(SearchHotels args) {
        if (args.city()==null || args.city().isBlank() || args.city().length()>50 ||
            (args.maxNightlyPrice()!=null && (!Double.isFinite(args.maxNightlyPrice()) || args.maxNightlyPrice()<0 || args.maxNightlyPrice()>100000)))
            return ToolResult.error("INVALID_ARGUMENT","请提供城市和有效的每晚预算。");
        String sql="SELECT id,name,city,address,star_rating,min_price,facilities FROM hotel WHERE status=1 AND city=?";
        var params=new ArrayList<Object>(); params.add(args.city().trim());
        if (args.maxNightlyPrice()!=null) { sql+=" AND min_price<=?"; params.add(args.maxNightlyPrice()); }
        var hotels=jdbc.queryForList(sql+" ORDER BY min_price,id LIMIT 10",params.toArray());
        var evidence=hotels.stream().map(h -> evidence("hotel:"+h.get("id"),String.valueOf(h.get("name")),
            h.get("name")+"，"+h.get("city")+"，资料起价"+h.get("min_price")+"元/晚；实际日期价格与房态需进一步查询。" )).toList();
        String summary=evidence.isEmpty()?"没有满足城市和预算的酒店，尚未放宽条件。":String.join("\n",evidence.stream().map(Evidence::excerpt).toList());
        return ToolResult.success(Map.of("hotels",hotels,"summary",summary,"priceType","catalog_from_price"),evidence);
    }
    public ToolResult details(HotelId args) {
        var hotel=hotel(args.hotelId());
        if (hotel==null) return ToolResult.error("HOTEL_NOT_FOUND","没有找到可公开查询的酒店。");
        var rooms=jdbc.queryForList("SELECT id,name,price,bed_count,bed_type,breakfast_count,cancel_policy FROM room_type WHERE hotel_id=? AND status=1 ORDER BY id LIMIT 30",args.hotelId());
        var fact=evidence("hotel:"+args.hotelId(),String.valueOf(hotel.get("name")),hotel.get("name")+"；地址："+hotel.get("address")+"；设施："+hotel.get("facilities"));
        return ToolResult.success(Map.of("hotel",hotel,"roomTypes",rooms,"summary",fact.excerpt(),"note","房型资料不代表所选日期可订"),List.of(fact));
    }
    public ToolResult availability(Availability args) {
        LocalDate in,out;
        int requested=args.roomCount()==null?1:args.roomCount();
        try {
            in=LocalDate.parse(args.checkIn()); out=LocalDate.parse(args.checkOut());
            if (in.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))) || !out.isAfter(in) || ChronoUnit.DAYS.between(in,out)>30 || requested<1 || requested>10)
                throw new IllegalArgumentException();
        } catch (Exception ex) { return ToolResult.error("INVALID_ARGUMENT","请提供未来的入住日期、其后的退房日期及1至10间房，最多查询30晚。"); }
        var hotel=hotel(args.hotelId());
        if (hotel==null) return ToolResult.error("HOTEL_NOT_FOUND","没有找到可公开查询的酒店。");
        int nights=(int)ChronoUnit.DAYS.between(in,out);
        var rooms=jdbc.queryForList("SELECT id,name FROM room_type WHERE hotel_id=? AND status=1 ORDER BY id LIMIT 30",args.hotelId());
        var results=new ArrayList<Map<String,Object>>();
        var texts=new ArrayList<String>();
        for (var room:rooms) {
            var daily=jdbc.queryForList("SELECT stay_date,available_rooms,nightly_price,updated_at FROM room_inventory_daily WHERE room_type_id=? AND stay_date>=? AND stay_date<? ORDER BY stay_date",room.get("id"),in,out);
            boolean complete=daily.size()==nights;
            int minimum=daily.stream().mapToInt(d->((Number)d.get("available_rooms")).intValue()).min().orElse(0);
            String status=!complete?"UNKNOWN":minimum<requested?"UNAVAILABLE":"AVAILABLE";
            BigDecimal total=daily.stream().map(d->new BigDecimal(d.get("nightly_price").toString())).reduce(BigDecimal.ZERO,BigDecimal::add).multiply(BigDecimal.valueOf(requested));
            var row=new LinkedHashMap<String,Object>(); row.put("roomTypeId",room.get("id")); row.put("name",room.get("name"));
            row.put("status",status); row.put("minimumAvailable",complete?minimum:null); row.put("totalPrice",complete?total:null);
            row.put("nights",nights); row.put("requestedRooms",requested); row.put("knownDays",daily.size()); results.add(row);
            texts.add(room.get("name")+"："+(status.equals("UNKNOWN")?"日期库存不完整，无法确认可订":status.equals("UNAVAILABLE")?"至少一天剩余房量不足":
                "库存快照满足"+requested+"间，"+nights+"晚总计"+total+"元"));
        }
        String summary=in+"至"+out+"，"+String.join("；",texts)+"。本查询不锁房，价格和库存可能变化。";
        String id="inventory:"+args.hotelId()+":"+in+":"+out;
        return ToolResult.success(Map.of("rooms",results,"summary",summary,"snapshotOnly",true),List.of(evidence(id,hotel.get("name")+"日期房态",summary)));
    }
    public ToolResult policies(PolicyQuery args) {
        if (hotel(args.hotelId())==null) return ToolResult.error("HOTEL_NOT_FOUND","请先明确可查询的酒店。");
        return knowledge.search(args.query(),args.hotelId());
    }
    public ToolResult route(RouteQuery args) {
        var hotel=hotel(args.hotelId());
        if (hotel==null) return ToolResult.error("HOTEL_NOT_FOUND","请先明确可查询的酒店。");
        if (args.destination()==null || args.destination().isBlank() || args.destination().length()>120)
            return ToolResult.error("INVALID_ARGUMENT","请提供明确的目的地名称或地址。");
        if (props.getAmapKey().isBlank()) return ToolResult.error("MAP_NOT_CONFIGURED","地图服务尚未配置，无法核实步行距离和时长。");
        if (hotel.get("latitude")==null || hotel.get("longitude")==null) return ToolResult.error("LOCATION_UNKNOWN","酒店坐标缺失，请先向酒店确认地址。");
        try {
            JsonNode geo=amap("/v3/geocode/geo",Map.of("address",args.destination(),"city",hotel.get("city").toString()));
            if (geo.path("geocodes").size()!=1) return ToolResult.error("LOCATION_AMBIGUOUS","目的地不明确，请补充详细地址。");
            String destination=geo.path("geocodes").get(0).path("location").asText();
            String origin=hotel.get("longitude")+","+hotel.get("latitude");
            JsonNode routing=amap("/v3/direction/walking",Map.of("origin",origin,"destination",destination));
            JsonNode first=routing.path("route").path("paths").path(0);
            int distance=first.path("distance").asInt(-1), duration=first.path("duration").asInt(-1);
            if (distance<0 || duration<0) return ToolResult.error("MAP_UNAVAILABLE","没有查到有效步行路线。");
            String summary="到"+args.destination()+"步行约"+distance+"米，约"+Math.round(duration/60.0)+"分钟；路线为地图查询结果，仅供出行参考。";
            return ToolResult.success(Map.of("summary",summary,"distanceMeters",distance,"durationSeconds",duration),List.of(
                new Evidence("route:"+args.hotelId(),"高德步行路线","高德地图 Web 服务",summary,props.getDataKind())));
        } catch (Exception ex) { return ToolResult.error("MAP_UNAVAILABLE","地图查询暂时失败，请稍后重试。"); }
    }
    private JsonNode amap(String path,Map<String,String> params) throws Exception {
        var all=new LinkedHashMap<>(params); all.put("key",props.getAmapKey());
        String query=String.join("&",all.entrySet().stream().map(e->e.getKey()+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).toList());
        var request=HttpRequest.newBuilder(URI.create("https://restapi.amap.com"+path+"?"+query)).timeout(Duration.ofSeconds(5)).GET().build();
        for (int attempt=0;attempt<2;attempt++) {
            try {
                var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode()==429 || response.statusCode()>=500) && attempt==0) continue;
                if (response.statusCode()!=200 || response.body().length()>200000) throw new IllegalStateException("Map response unavailable");
                var result=json.readTree(response.body());
                if (!"1".equals(result.path("status").asText())) throw new IllegalStateException("Map business failure");
                return result;
            } catch (java.io.IOException ex) { if (attempt==1) throw ex; }
        }
        throw new IllegalStateException("Map unavailable");
    }
}
