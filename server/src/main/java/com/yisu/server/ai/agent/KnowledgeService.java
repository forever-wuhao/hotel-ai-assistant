package com.yisu.server.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import static com.yisu.server.ai.agent.AgentDtos.*;

@Service
public class KnowledgeService {
    private final AgentProperties props;
    private final EmbeddingModel embeddings;
    private final ObjectMapper json;
    private final ResourceLoader resources;
    private volatile SimpleVectorStore store;
    public KnowledgeService(AgentProperties props, EmbeddingModel embeddings, ObjectMapper json, ResourceLoader resources) {
        this.props=props; this.embeddings=embeddings; this.json=json; this.resources=resources;
    }
    private synchronized SimpleVectorStore index() throws Exception {
        if (store!=null) return store;
        if (props.getKnowledgeManifest().isBlank()) throw new IllegalStateException("Knowledge manifest missing");
        var manifestResource=resources.getResource(props.getKnowledgeManifest());
        byte[] raw=manifestResource.getContentAsByteArray();
        JsonNode manifest=json.readTree(raw);
        if (!props.getDataKind().equals(manifest.path("dataKind").asText())) throw new IllegalArgumentException("Knowledge data kind mismatch");
        var digest=MessageDigest.getInstance("SHA-256");
        digest.update(raw);
        digest.update((props.getMode()+props.getBaseUrl()+props.getEmbeddingModel()+":split-v1").getBytes(StandardCharsets.UTF_8));
        var docs=new ArrayList<Document>();
        var documentIds=new HashSet<String>();
        for (JsonNode spec: manifest.path("documents")) {
            String id=spec.path("id").asText(), file=spec.path("file").asText();
            if (!id.matches("[a-zA-Z0-9_-]{1,80}") || !documentIds.add(id) || !file.matches("[a-zA-Z0-9_-]+\\.md")
                || !spec.path("hotelId").isIntegralNumber() || spec.path("hotelId").asInt(-1)<0
                || spec.path("title").asText().isBlank() || spec.path("source").asText().isBlank())
                throw new IllegalArgumentException("Invalid/duplicate knowledge source");
            byte[] bytes=manifestResource.createRelative(file).getContentAsByteArray();
            if (bytes.length>500_000) throw new IllegalArgumentException("Policy document too large");
            digest.update(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            int chunk=0;
            for (String section:text.split("(?m)(?=^#{1,3} )")) {
                section=section.trim(); if (section.isEmpty()) continue;
                for (int start=0;start<section.length();start+=520) {
                    String content=section.substring(start,Math.min(start+600,section.length()));
                    docs.add(Document.builder().id("policy:"+id+":"+(chunk++)).text(content)
                        .metadata(Map.of("hotelId",spec.path("hotelId").asInt(0),"title",spec.path("title").asText(),
                            "source",spec.path("source").asText(),"dataKind",props.getDataKind())).build());
                    if (start+600>=section.length()) break;
                }
            }
        }
        if (docs.isEmpty() || docs.size()>2000) throw new IllegalArgumentException("Knowledge must contain 1..2000 chunks");
        String fingerprint=HexFormat.of().formatHex(digest.digest());
        Path dir=Path.of(props.getCacheDirectory()); Files.createDirectories(dir);
        Path cache=dir.resolve(fingerprint+".json");
        var built=SimpleVectorStore.builder(embeddings).build();
        if (Files.isRegularFile(cache)) built.load(cache.toFile());
        else {
            built.add(docs);
            Path temp=Files.createTempFile(dir,"index-",".tmp");
            try {
                built.save(temp.toFile());
                Files.move(temp,cache,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        store=built; return built;
    }
    public ToolResult search(String query, Integer hotelId) {
        if (query==null || query.isBlank() || query.length()>500 || hotelId==null || hotelId<=0)
            return ToolResult.error("INVALID_ARGUMENT","请提供具体酒店和政策问题。");
        try {
            var docs=index().similaritySearch(SearchRequest.builder().query(query).topK(4).similarityThreshold(.15)
                .filterExpression("hotelId == "+hotelId+" || hotelId == 0").build());
            var evidence=docs.stream().map(d -> new Evidence(d.getId(),(String)d.getMetadata().get("title"),
                (String)d.getMetadata().get("source"), d.getText(),props.getDataKind())).toList();
            return ToolResult.success(Map.of("summary",evidence.isEmpty()?"没有检索到可引用的政策，请联系酒店核实。":
                String.join("\n",evidence.stream().map(Evidence::excerpt).toList()), "matches",evidence),evidence);
        } catch (Exception ex) {
            return ToolResult.error("KNOWLEDGE_UNAVAILABLE","政策资料暂时不可用，不能据此承诺入住或退订条件。");
        }
    }
}
