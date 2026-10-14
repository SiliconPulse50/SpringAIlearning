package com.example.ragdemo.service;
/* 单个文件的入库结果 —— 台账 */
public record IngestRecord(
        String file,      // 相对路径，如 api/image/image.adoc
        String category,  // official / notes
        int chars,        // 读到的字符数（0 说明没抽到内容）
        int chunks,       // 切成几块
        String status,    // OK / FAIL
        String error      // 失败原因，成功时为 null
)
{
    // 两个便捷构造：省去调用处写一堆 null
    public static IngestRecord ok(String file, String category, int chars, int chunks) {
        return new IngestRecord(file, category, chars, chunks, "OK", null);
    }

    public static IngestRecord fail(String file, String category, String error) {
        return new IngestRecord(file, category, 0, 0, "FAIL", error);
    }

}
