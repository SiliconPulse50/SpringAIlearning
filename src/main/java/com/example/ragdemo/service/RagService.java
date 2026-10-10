package com.example.ragdemo.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    /** 百炼 embeddings 单次输入条数有上限，分批送 */
    private static final int BATCH = 10;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagService(VectorStore vectorStore, ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        // Builder 是 prototype 作用域，每个类各拿一份，不会污染 ChatController 的配置
        // 这里也挂上日志 advisor：RAG 拼出来的完整 prompt 会打进控制台，3.2 排错全靠它
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
    }

    /*  纯文本入库，返回切成了几块 */
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

   /*
   读文件入库，参数用resource 而不是MultipartFile ,让service不依赖web层
    */
    public int ingest(Resource resource) {
        // Extract同一个类型的List<Documents>一直传下去
        List<Document> documents = new TikaDocumentReader(resource).read();

        // 最关键的一行：确认"到底读到字了没有"
        //源码里面：Map <String,Object> 取值 ->返回值类型是 Object 拼字符串自动toString()
        documents.forEach(d -> System.out.println(
                "读取到 " + d.getText().length() + " 字符, source="
                + d.getMetadata().get(TikaDocumentReader.METADATA_SOURCE)));
        // Transform
        List<Document> chunks = TokenTextSplitter.builder().build().apply(documents);

        // Load（和 3.1 一样分批）
        for (int i = 0; i < chunks.size(); i += BATCH) {
            vectorStore.add(chunks.subList(i, Math.min(i + BATCH, chunks.size())));
        }
        //文件入库完成
        System.out.println("文件入库完成：切成 " + chunks.size() + " 块");
        return chunks.size();
    }

    public String ask(String question) {
        // 1. 检索
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(5)
                .similarityThresholdAll()      // 先不做阈值过滤（比"注释掉"更明确）
                .build();

        List<Document> hits = vectorStore.similaritySearch(request);

        // 2. 把命中结果打出来
        hits.forEach(d -> System.out.println(
                "score:[" + d.getScore() + "] " + d.getText().substring(0, Math.min(60, d.getText().length()))));

        if (hits.isEmpty()) {
            return "没有检索到相关内容,向量库里可能还没有数据";
        }

        // 3. 拼上下文
        String context = hits.stream()
                .map(Document::getText)                    // ← 不是 getContent spring 2.0 已没有这个方法
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. 组装 prompt 并调用，RAG的prompt模板，结尾。formatted (context,question)
        return chatClient.prompt()
                .user("""
                    请根据以下参考资料回答问题。
                    如果参考资料中没有相关信息，直接回答"根据提供的资料无法回答该问题"，不要编造。

                    参考资料：
                    %s

                    问题：%s
                    """.formatted(context, question))
                .call()
                .content();
    }
}
