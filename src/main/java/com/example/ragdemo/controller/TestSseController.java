
package com.example.ragdemo.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@RestController
public class TestSseController {

    @GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> testStream() {
        List<String> words = List.of("你好", "，", "我", "是", "AI", "助手", "。");
        return Flux.fromIterable(words)
                .delayElements(Duration.ofMillis(500));
    }
}