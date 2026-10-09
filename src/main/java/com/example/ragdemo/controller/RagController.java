package com.example.ragdemo.controller;

import com.example.ragdemo.Service.RagService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /* 纯文本入库：先用它把 ETL 链路跑通，隔离  Tika 这一层 */
    @PostMapping("/ingest-text")
    public String ingestText(@RequestParam String text) {
        return "已入库 " + ragService.ingest(text) + " 个块";
    }
}