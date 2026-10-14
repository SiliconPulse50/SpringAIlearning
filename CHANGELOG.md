# CHANGELOG

## v0.1 数据层就绪（日期：2026-10-14）
- 数据：Spring AI 官方文档 v2.0.0，115 份 .adoc，1,723,250 字符
- 入库：TRUNCATE 后全量入库 → **537 块，115 个来源，失败率 0%**
- 参数：chunkSize=800、similarityThresholdAll（不过滤）、bailian text-embedding-v4(1024 维)
- 交叉验证：台账 chunks 合计 = 库里块数 = 537；chars 合计 = 源目录字符数
- 幂等：同目录跑两次行数不变（已验证）

## v0.0 修正元数据身份 bug（日期：2026-10-14）
- 现象：台账 115 份全 OK，但库里只有 111 个来源、530 块（台账合计 537）
- 定位：**对比"台账 chunks 合计"与"库里块数"，差 7 块** → 反查发现 3 组同名文件
  （speech.adoc / transcriptions.adoc / index.adoc）互相覆盖
- 根因：source 用了裸文件名当身份；delete-by-source 把另一个同名文件的数据删了
- 修复：source 改用相对路径（api/audio/speech.adoc 唯一）
  category 从 contains("notes") 改成 startsWith("notes/")
  version 从 relativeDir 取（原来 rel 里根本没有版本号）
- 意义：元数据是引用和过滤的基础；重名会导致引用不可信 + 每次入库静默丢数据