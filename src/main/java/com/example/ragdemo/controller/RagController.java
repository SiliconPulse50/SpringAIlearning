package com.example.ragdemo.controller;

import com.example.ragdemo.service.RagService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        return ragService.ask(question);
    }
    /* 文件入库 */
    @PostMapping("/ingest")
    public String ingest(@RequestParam("file") MultipartFile file) {
        System.out.println("收到文件：" + file.getOriginalFilename() + "，大小 " + file.getSize() + " 字节");
        return "已入库 " + ragService.ingest(file.getResource()) + " 个块";
    }
}