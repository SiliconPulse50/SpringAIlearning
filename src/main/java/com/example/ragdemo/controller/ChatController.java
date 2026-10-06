
package com.example.ragdemo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
// 测试页面不一定和后端同源：直接双击 html（file://）或用 IDEA 内置服务器（localhost:63342）打开时，
// 浏览器会带上 Origin 头做跨域校验，没有这段配置 EventSource 会直接报错、一个字都收不到。
// 只用于本地练习；真正上线时请把 origins 收窄成具体域名。
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        // SimpleLoggerAdvisor 必须显式注册才会打印 Prompt / Response，
        // 光在 yml 里调日志级别是看不到任何东西的。
        // 它内部按 DEBUG 级别输出，日志里出现的是 SimpleLoggerAdvisor 这个 logger。
        this.chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @GetMapping("/simple")
    public String simple(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
 /*
 设置system，行为准则
  */
    @GetMapping("/persona")
    public String personaChat(@RequestParam String message) {
        return chatClient.prompt()
                .system("""
            你是一个资深 Java 后端工程师，专注于 Spring 生态。
            回答需简洁准确，给出可运行的代码示例。
            如果问题与 Java 或 Spring 无关，礼貌地拒绝回答。
            """)
                .user(message)
                .call()
                .content();
    }
/*
设置温度和最大令牌数，控制输出
 */
    @GetMapping("/tuned")
    public String tunedChat(@RequestParam String message,
                            @RequestParam(defaultValue = "0.2") Double temp,
                            @RequestParam(defaultValue = "300") Integer maxTokens) {
        return chatClient.prompt()
                .user(message)
                .options(ChatOptions.builder()
                        .temperature(temp)
                        .maxTokens(maxTokens))
                .call()
                .content();
    }
}
