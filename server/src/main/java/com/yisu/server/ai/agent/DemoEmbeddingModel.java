package com.yisu.server.ai.agent;

import java.util.ArrayList;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

/** Deterministic lexical hashing ONLY for offline plumbing tests, not semantic embeddings. */
public class DemoEmbeddingModel implements EmbeddingModel {
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        var results = new ArrayList<Embedding>();
        for (int i=0; i<request.getInstructions().size(); i++) results.add(new Embedding(vector(request.getInstructions().get(i)), i));
        return new EmbeddingResponse(results);
    }
    @Override public float[] embed(Document document) { return vector(document.getText()); }
    @Override public int dimensions() { return 512; }
    private float[] vector(String text) {
        float[] vector = new float[512];
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (int n=1; n<=2; n++) for (int i=0; i+n<=normalized.length(); i++) {
            int hash=normalized.substring(i,i+n).hashCode();
            vector[Math.floorMod(hash,512)] += 1;
        }
        double norm=0; for (float value:vector) norm+=value*value;
        if (norm==0) vector[0]=1;
        else for (int i=0;i<vector.length;i++) vector[i]/=(float)Math.sqrt(norm);
        return vector;
    }
}
