package com.example.ragdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


    @ConfigurationProperties(prefix = "rag")
    public record RagProperties(
            String docsRoot,
            int batchSize,
            int chunkSize,
            int topK,
            double similarityThreshold
    )
    {

    }
// record 语法 final类+全参constructor+a()
