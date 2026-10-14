### 学习spring ai
# Spring AI 文档助手（Spring AI Docs RAG）

> 一句话：把 Spring AI 官方文档变成能"问"的知识库，回答带来源引用。

<!-- 上面这句是你的电梯陈述。要求：一个不懂技术的人听完能知道你在解决什么 -->

## 1. 解决什么问题

**给谁用**：个人使用（一个正在学 Spring AI 的 Java 开发者）

**原来的痛点**：
- 查找时候版本1.0与2.0混在一起，容易造成学习者的误读。
- 对于初学者而言，文档复杂，查找时耗费精力，无法得到直接的答案（例：想知道 v2.0.0 里某配置项的默认值，得 Ctrl+F 翻好几篇）

**这个项目解决它的方式**：
提问 → 检索相关段落 → 让模型基于段落回答 → **标出答案来自哪份文档的哪一段**

**成功标准（可验证）**：
- ___（例：问 30 个真实问题，召回率@5 ≥ 70%）
- ___（例：每个回答都带 [1][2] 引用，抽查 5 个全部对得上来源）

> ⚠️ 判断你写没写到位：**"成功标准"必须是数字或可观测的行为，不能是"效果不错"。**

## 2. 数据

| 项 | 内容 |
|---|---|
| 来源 1 | Spring AI 官方文档 `v2.0.0` tag，仓库 `spring-ai-docs/src/main/antora/modules/ROOT/pages` |
| 来源 2 | 我自己的学习笔记 ___ 份 |
| 文档数 | 官方 ___ 份 `.adoc` + 笔记 ___ 份 |
| 总字数 | 约 ___ 万字 |
| 分块数 | ___ 个块（`vector_store` 表行数） |
| 更新方式 | `git pull` 拉新文档 → 按 `source` 先删后插（幂等），不产生重复 |
| 分类字段 | `category` = `official` / `notes`（检索时可用元数据过滤限定范围）|
| 版本字段 | `version` = `v2.0.0`（**锁定版本，保证评测结果可复现**）|
| 合规说明 | 全部为公开文档 + 本人笔记，无涉密/个人信息；embedding 走阿里云百炼 |

<!-- 怎么拿到这些数字（不写代码）：
     PowerShell 数文件：
       (Get-ChildItem docs -Recurse -File -Include *.adoc,*.md).Count
     统计总字符数：
       (Get-ChildItem docs -Recurse -File -Include *.adoc,*.md | Get-Content -Raw | Measure-Object -Character).Characters
     数块数：
       docker exec -i rag-pgvector psql -U postgres -d springai -c "select count(*) from vector_store;" -->

## 3. 怎么跑起来

**前置要求**：JDK 17 / Maven / Docker / 一个 DeepSeek key（对话）+ 一个百炼 key（embedding）

```bash
# ① 起向量库
docker start rag-pgvector
docker exec -i rag-pgvector psql -U postgres -d springai -c "select count(*) from vector_store;"
#    返回数字即数据库就绪

# ② 配环境变量（IDEA：Run/Debug Configurations → Environment variables）
OPENAI_API_KEY=sk-xxx;DASHSCOPE_API_KEY=sk-xxx

# ③ 启动
Shift+F10   # IDEA 里 Run

# ④ 批量入库官方文档（幂等，可重复执行）
#    POST /api/rag/ingest-dir?path=docs/official

# ⑤ 提问
#    GET  /api/rag/ask?question=PgVector 的默认维度是多少
```

**接口一览**：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/rag/ingest` | POST | 上传单文件（multipart）|
| `/api/rag/ingest-text` | POST | 纯文本入库（调试用）|
| `/api/rag/ingest-dir` | POST | 批量入库目录 + 返回台账 |
| `/api/rag/ask` | GET | 问答，返回答案 + 引用来源 |
| `/api/rag/eval` | GET | 跑评测集，返回召回率/延迟/成本 |

## 4. 效果

> 评测集：___ 题（事实型 ___ / 操作型 ___ / 对比型 ___ 各 ___ 题），
> 每题含标准答案与正确来源，人工核对。
> 指标定义：**召回率@5 = topK=5 的检索结果里包含"正确来源文件"的题目占比**

| 指标 | 数值 | 说明 |
|---|---|---|
| 召回率@5 | **待测（Day3）** | 检索层质量，与生成质量无关 |
| 答案准确率（抽样 10 题人工判断）| 待测 | — |
| 引用正确率 | 待测 | 抽查 5 题，[n] 与来源对得上 |
| 检索延迟 p95 | 待测 | — |
| 端到端延迟 p95 | 待测 | 检索 + 生成 |
| 单次问答成本 | 待测 | `usage.totalTokens` × 单价 |
| 入库失败率 | 待测 | 台账里 FAIL 数 / 总数，**目标 < 10%** |

<!-- 以上数字全部在"阶段 3 指标"测完后回填。
     现在留"待测"是正确的，不要猜。 -->

## 5. 我做过的关键取舍

<!-- 这是 README 最有价值的一节。格式固定：决策 / 备选 / 选了 / 为什么 / 代价 -->

| 决策 | 备选方案 | 我的选择 | 为什么 | 代价 |
|---|---|---|---|---|
| 向量库 | 内存 SimpleVectorStore / PgVector | **PgVector** | ___（数据量 ___ 份需持久化；重启不丢；能用 SQL 直接验证）| 多一个容器要维护 |
| Embedding | OpenAI / Ollama 本地 / 百炼 | **阿里云百炼 text-embedding-v4（1024 维）** | ___（DeepSeek 没有 embedding 接口；本地 Ollama 未安装；百炼已有 key）| 依赖网络，有成本 |
| 分块粒度 | 800（默认）/ 400 / 自定义 | **待测后决定** | ___（用评测集对比召回率）| — |
| 检索阈值 | 不过滤 / 0.5 / 0.7 | **待测后决定** | 先不过滤拿基线，再按实测分数分布设线 | 设高会漏召回 |
| 向量库距离 | COSINE / EUCLIDEAN / 内积 | **COSINE_DISTANCE** | 文本语义相似度用余弦是通行做法 | — |
| Reader | 全用 Tika | **按扩展名分派**（`.adoc/.md`→`TextReader`，PDF/Word→`TikaDocumentReader`）| 纯文本不需要 Tika 猜格式，且少一层解析失败风险 | 多几行分派逻辑 |

> ⚠️ 表格里"为什么"那一列，**必须是你自己的判断**。写不出来就说明这个决策不该现在做——先去测，测完再写。

## 6. 踩过的坑（和定位方法）

<!-- 只写你亲手解决的；每条必须是"现象 → 怎么定位 → 结论"三段式 -->

| 现象 | 我怎么定位的 | 结论 |
|---|---|---|
| 全局异常处理器编译不过 | 用真实依赖集编译，报 `程序包 org.springframework.ai.retry 不存在` | 教程是 1.0 的，项目是 2.0；1.0 的异常类在 2.0 已不存在 |
| 异常处理器能编译但**永远不触发**（最危险一类） | 故意用错误 key 打接口，发现返回的是 Spring 默认 500 而不是我的 JSON；再去日志里看 root cause 的真实异常类型 | 2.0 抛的是 `com.openai.errors.UnauthorizedException`，不是 1.0 的 `NonTransientAiException`。**改写后实测返回友好 JSON** |
| 接口返回 401 | **分层验证**：容器 → 依赖 → 配置 → 应用 → 接口；用 `[Environment]::GetEnvironmentVariable` 查三个作用域 | embedding 的 `DASHSCOPE_API_KEY` 三个作用域都没设（yml 里 `${VAR:}` 取不到就变空串）|
| 重复入库后检索出现重复内容 | 读 PgVectorStore 字节码，看到写入是 `ON CONFLICT (id) DO UPDATE`；再查 `Document` 默认 `RandomIdGenerator` | **ID 随机 → 每次都是"新行"**，不会覆盖。改为按 `source` 先删后插 |
| 照代码抄编译报错 | `Ctrl+Q` 看文档、`javap -v` 看 `@Deprecated(since, forRemoval)` | 教程基于 1.0.0-M 时代 API；**写之前先核实本地 jar 里的真实签名** |
| `spring.ai.retry.*` 配了却不生效 | 反编译 `OpenAiChatModel`，发现它没有 `RetryTemplate` 字段 | 2.0 的重试由 openai-java SDK 管，开关是 `spring.ai.openai.chat.max-retries` |

**一句话总结我学到的方法论**：
> 1. 照教程写之前，先确认 API 在你本地 jar 里的真实样子
> 2. 一个报错里如果有多个变量，先分层隔离，别同时改两处
> 3. "配置了"≠"生效了"，一定要用一次故意失败来验证错误路径

## 7. 我不做什么（以及为什么）

| 不做 | 原因 |
|---|---|
| 前端页面 | 不体现 RAG 核心能力，浏览器调接口已足够验证 |
| 部署到公网 | 需要域名/证书/网络配置，与核心能力无关；本地 `docker compose` 已可复现 |
| 多用户与权限 | 单用户场景下无必要；`category` 元数据过滤已体现数据隔离的设计思路 |
| 混合检索 / Rerank / Query 改写 | 各自是独立课题，会稀释本次目标；列为下一步 |
| 多轮对话记忆 | 同上，需要 `ChatMemory`，属于独立模块 |

## 8. 下一步

- [ ] 换 embedding 模型（1024 → 其他维度）对比召回率与成本
- [ ] 混合检索（向量 + BM25 关键词）解决专有名词召回差的问题
- [ ] `TokenTextSplitter` 之外自研分块器（按 `.adoc` 的层级结构切）
- [ ] 接入 Spring AI 的 `RetrievalAugmentationAdvisor` 替换手写的检索链
- [ ] 部署：`docker compose` 打包应用 + 数据库

---

**技术栈**：Spring Boot 4.0.8 / Spring AI 2.0.0 / PostgreSQL 16 + pgvector / 阿里云百炼（embedding）/ DeepSeek（对话）
