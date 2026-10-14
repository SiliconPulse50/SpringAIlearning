package com.example.ragdemo.service;

import com.example.ragdemo.config.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.id.JdkSha256HexIdGenerator;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.example.ragdemo.config.RagProperties;
@Service
public class RagService {

    /** 百炼 embeddings 单次输入条数有上限，分批送 */
    private static final int BATCH = 10;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagProperties props;

    // RagProperties 在 config 包，必须 import
    public RagService(VectorStore vectorStore, ChatClient.Builder builder, RagProperties props) {
        this.vectorStore = vectorStore;
        // Builder 是 prototype 作用域，每个类各拿一份，不会污染 ChatController 的配置
        // 这里也挂上日志 advisor：RAG 拼出来的完整 prompt 会打进控制台，3.2 排错全靠它
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.props=props;
        System.out.println("=== RagProperties: docsRoot=" + props.docsRoot()
                + ", chunkSize=" + props.chunkSize()
                + ", batchSize=" + props.batchSize()
                + ", topK=" + props.topK()
                + ", threshold=" + props.similarityThreshold());
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

        // 3. 拼上下文,变成流，中间操作Document->string ,终端操作string 拼成一个
        String context = hits.stream()
                .map(Document::getText)                    // 方法引用： 不是 getContent ,spring 2.0 已没有这个方法
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

    /* Document 是不可变的：加元数据必须重建。所有入库路径都复用它 */
    private Document withMeta(Document source, Map<String, Object> extra) {
        Document.Builder b = Document.builder()
                .idGenerator(new JdkSha256HexIdGenerator())   // 内容哈希做 id
                .text(source.getText())
                .metadata(source.getMetadata());              // 保留 Reader 塞的 source
        extra.forEach(b::metadata);                           // 方法引用：BiConsumer
        return b.build();
    }
    private DocumentReader readerFor(Resource resource, String ext) {
        return switch (ext) {
            case "adoc", "md", "txt" -> new TextReader(resource);          // 纯文本：不用 Tika 猜格式
            default -> new TikaDocumentReader(resource);                    // pdf/docx/pptx/html
        };
    }

    public List<IngestRecord> ingestDirectory(String relativeDir) {
        Path root = Path.of(props.docsRoot(), relativeDir);
        List<IngestRecord> ledger = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {                    // ★ 必须 try-with-resources
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".adoc") || n.endsWith(".md") || n.endsWith(".pdf") || n.endsWith(".docx");
                    })
                    .sorted()                                             // 稳定顺序，便于排查
                    .toList();                                            // 终端操作：真正开始遍历

            for (Path file : files) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                String version = versionOf(relativeDir);
                try {
                    ledger.add(ingestOne(file, rel,version));
                } catch (Exception e) {
                    // ★ 单个文件失败绝不能中断整批 —— demo 和项目的分界线
                    System.err.println("[入库失败] " + rel + " → " + e.getMessage());
                    ledger.add(IngestRecord.fail(rel, categoryOf(relativeDir), e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("目录读取失败: " + root, e);
        }

        // 汇总（这行会成为你 README 第 4 节的"入库失败率"）
        long ok = ledger.stream().filter(r -> "OK".equals(r.status())).count();
        System.out.printf("入库汇总: 共 %d 份, 成功 %d, 失败 %d%n", ledger.size(), ok, ledger.size() - ok);
        return ledger;
    }
    private IngestRecord ingestOne(Path file, String rel,String version) {
        String sourceKey=rel;
        String category = categoryOf(rel);
        Resource resource = new FileSystemResource(file);
        String ext = extOf(file.getFileName().toString());

        // ① 先读 + 分块（内存操作，失败不会破坏已有数据）
        List<Document> raw = readerFor(resource, ext).read();
        int chars = raw.stream().mapToInt(d -> d.getText().length()).sum();
        if (chars == 0) {
            return IngestRecord.fail(rel, category, "读到 0 字符（扫描件？编码错？）");
        }
        // 1. 先计算 sourceName
        String sourceName = file.getFileName().toString();


        // 2. 定义包含所有字段的 extra（替换掉原来的那个）
//        Map<String, Object> extra = Map.of(
//                "source", sourceName,          // ★ 现在包含 source 了
//                "category", category,
//                "version", versionOf(rel),
//                "path", rel
//        );
        Map<String, Object> extra = Map.of(
                "source", sourceKey,                // ← 改这里
                "category", categoryOf(rel),
                "version", version,                 // ← 改这里：由调用方传进来
                "path", rel
        );
        List<Document> withMeta = raw.stream().map(d -> withMeta(d, extra)).toList();

        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(props.chunkSize())
                .build()
                .apply(withMeta);


        // ② 删旧数据（幂等的关键）
        //    delete 需要 Filter.Expression：FilterExpressionBuilder.eq(...) 返回 Op，Op.build() 得到 Expression
        //vectorStore.delete(new FilterExpressionBuilder().eq("source", file.getFileName().toString()).build());
        vectorStore.delete(new FilterExpressionBuilder().eq("source", sourceKey).build());


        // ③ 分批写入
        for (int i = 0; i < chunks.size(); i += props.batchSize()) {
            vectorStore.add(chunks.subList(i, Math.min(i + props.batchSize(), chunks.size())));
        }

        System.out.println("[OK] " + rel + " → " + chars + " 字符, " + chunks.size() + " 块");
        return IngestRecord.ok(rel, category, chars, chunks.size());
    }
    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
    // ★ 修复 2：按路径段判断分类
    private String categoryOf(String rel) {
        return rel.startsWith("notes/") ? "notes" : "official";
    }

    private String versionOf(String rel) {
        // 从路径里抓 v2.0.0 这种版本号
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("v\\d+(\\.\\d+)+").matcher(rel);
        return m.find() ? m.group() : "unknown";
    }
}
