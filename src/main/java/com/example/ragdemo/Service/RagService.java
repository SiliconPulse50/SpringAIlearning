package com.example.ragdemo.Service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    /** 百炼 embeddings 单次输入条数有上限，分批送 */
    private static final int BATCH = 10;

    private final VectorStore vectorStore;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 纯文本入库，返回切成了几块 */
    public int ingest(String text) {
        List<Document> documents = List.of(new Document(text));      // 先包成 Document
        //List<Document> chunks = new TokenTextSplitter().apply(documents); 把"怎么构造"从构造函数迁到了 builder，但没换类
        // 分块
        List<Document> chunks = TokenTextSplitter.builder().build().apply(documents);

        for (int i = 0; i < chunks.size(); i += BATCH) {              // 分批写库
            vectorStore.add(chunks.subList(i, Math.min(i + BATCH, chunks.size())));
        }

        System.out.println("入库完成：原始 " + documents.size() + " 篇 → 切成 " + chunks.size() + " 块");
        return chunks.size();
    }
}
