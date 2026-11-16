package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import static org.assertj.core.api.Assertions.*;

class KnowledgeCacheTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();
    private void manifest(boolean withHotel,String kind) throws Exception {
        var doc=new LinkedHashMap<String,Object>(Map.of("id","policy","file","policy.md","title","取消政策","source","hotel-owned policy v1"));
        if(withHotel)doc.put("hotelId",1);
        Files.writeString(dir.resolve("manifest.json"),json.writeValueAsString(Map.of("dataKind",kind,"documents",List.of(doc))));
    }
    private KnowledgeService service() {
        var p=new AgentProperties();p.setMode("demo");p.setDataKind("demo");
        p.setKnowledgeManifest(dir.resolve("manifest.json").toUri().toString());p.setCacheDirectory(dir.resolve("cache").toString());
        return new KnowledgeService(p,new DemoEmbeddingModel(),json,new DefaultResourceLoader());
    }
    @Test void cacheReloadsAndChangesFingerprintWhenSourceChanges() throws Exception {
        manifest(true,"demo"); Files.writeString(dir.resolve("policy.md"),"# 取消政策\n入住前18点可免费取消。");
        var original=service().search("取消政策",1); assertThat(original.evidence()).isNotEmpty();
        assertThat(service().search("取消政策",1).evidence()).isEqualTo(original.evidence());
        Files.writeString(dir.resolve("policy.md"),"# 取消政策\n此房型不可取消。");
        assertThat(service().search("取消政策",1).evidence().get(0).excerpt()).contains("不可取消");
        try(var files=Files.list(dir.resolve("cache"))) { assertThat(files.count()).isEqualTo(2); }
    }
    @Test void missingHotelAndMismatchedProvenanceFailClosed() throws Exception {
        Files.writeString(dir.resolve("policy.md"),"取消政策");manifest(false,"demo");
        assertThat(service().search("取消政策",1).code()).isEqualTo("KNOWLEDGE_UNAVAILABLE");
        manifest(true,"live");
        assertThat(service().search("取消政策",1).code()).isEqualTo("KNOWLEDGE_UNAVAILABLE");
    }
}
