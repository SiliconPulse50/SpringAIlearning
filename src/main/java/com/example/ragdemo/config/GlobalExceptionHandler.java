package com.example.ragdemo.config;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/*
 * 全局异常处理：把 ai 服务抛出的异常翻译成友好 JSON，别让前端收到 500 堆栈。
 *
 * 重要（Spring AI 2.0.0 的坑）：
 *   org.springframework.ai.retry.NonTransientAiException / TransientAiException 这两个类
 *   只存在于 Spring AI 1.0.x（spring-ai-retry 模块）。2.0.0 的 OpenAI 集成换成了官方
 *   openai-java SDK，异常类型变成 com.openai.errors.*，1.0 时代的写法直接编译不过。
 *
 *   继承关系：OpenAIException (RuntimeException)
 *               ├─ OpenAIServiceException
 *               │    ├─ UnauthorizedException(401) / PermissionDeniedException(403)
 *               │    ├─ RateLimitException(429) / BadRequestException(400)
 *               │    ├─ InternalServerException(5xx) / UnexpectedStatusCodeException
 *               │    └─ SseException
 *               ├─ OpenAIIoException（连不上、超时）
 *               └─ OpenAIRetryableException
 *
 *   Spring 按「最具体的处理器」匹配，所以下面这些可以共存，最后一个兜底。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 401：key 无效 / 过期 / 没配 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> onUnauthorized(UnauthorizedException e) {
        return fail(HttpStatus.UNAUTHORIZED, "API Key 无效或已过期，请检查环境变量 OPENAI_API_KEY", e);
    }

    /** 403：key 有效但没权限用这个模型 */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> onForbidden(PermissionDeniedException e) {
        return fail(HttpStatus.FORBIDDEN, "当前账号无权访问该模型", e);
    }

    /** 429：限流或余额不足 */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> onRateLimit(RateLimitException e) {
        return fail(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁或额度不足，请稍后重试", e);
    }

    /** 400：参数问题，比如模型名写错 */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(BadRequestException e) {
        return fail(HttpStatus.BAD_REQUEST, "请求参数有误（检查 model 名、max_tokens 等）", e);
    }

    /** 5xx：对方服务端问题 */
    @ExceptionHandler(InternalServerException.class)
    public ResponseEntity<Map<String, Object>> onServerError(InternalServerException e) {
        return fail(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用，请稍后重试", e);
    }

    /** 连不上 / 读超时 */
    @ExceptionHandler(OpenAIIoException.class)
    public ResponseEntity<Map<String, Object>> onIoError(OpenAIIoException e) {
        return fail(HttpStatus.GATEWAY_TIMEOUT, "连接 AI 服务失败或超时，请检查网络与 base-url", e);
    }

    /** 兜底：其它 openai 异常 */
    @ExceptionHandler(OpenAIException.class)
    public ResponseEntity<Map<String, Object>> onOpenAiError(OpenAIException e) {
        return fail(HttpStatus.BAD_GATEWAY, "调用 AI 服务失败", e);
    }

    private ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message, Exception e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", message);
        // detail 方便本地排查，对外上线可以去掉，避免泄露上游细节
        body.put("detail", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
