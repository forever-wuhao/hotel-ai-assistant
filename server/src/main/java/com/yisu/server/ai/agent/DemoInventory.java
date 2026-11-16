package com.yisu.server.ai.agent;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoInventory implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public DemoInventory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (int offset=0; offset<14; offset++) {
            for (int room : new int[]{11,12,21,31}) {
                int available = room == 12 && offset == 2 ? 0 : 3;
                int price = switch(room) { case 11 -> 399; case 12 -> 459; case 21 -> 259; default -> 499; };
                jdbc.update("MERGE INTO room_inventory_daily (room_type_id,stay_date,available_rooms,nightly_price) KEY(room_type_id,stay_date) VALUES (?,?,?,?)",
                    room, today.plusDays(offset), available, price);
            }
        }
    }
}
