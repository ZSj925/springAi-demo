package Zsj.springai.exception;

import com.openai.errors.OpenAIException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import Zsj.springai.common.Result;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把所有异常统一包装成 Result 返回。
 * <p>
 * 处理顺序（Spring 自动按"最具体"匹配，不用手写）：
 * <pre>
 * BizException                    → 业务失败，状态码由异常自带
 * ConstraintViolationException    → 参数校验失败 400
 * MissingServletRequestParameter  → 缺必填参数 400
 * IllegalArgumentException        → 参数非法 400
 * NoResourceFoundException        → 路径不存在 404
 * OpenAIException                 → 模型服务故障 502 / 限流 429
 * AsyncRequestNotUsableException  → 客户端断开，不写响应体
 * Exception                       → 兜底
 * </pre>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常：代码主动抛出，状态码由异常自带 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 [{}]: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode())
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 参数校验失败（@NotBlank / @Size 等） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /** 少了必填参数，比如 /user/chat 没带 ?q= */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "缺少必填参数: " + e.getParameterName()));
    }

    /**
     * 参数非法。Spring AI 内部断言也抛这个，所以保留 400；
     * 但额外打一条 warn，避免"其实是代码 bug"的情况被静默当成客户端错误。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegal(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        log.debug("参数非法堆栈", e);
        return ResponseEntity.badRequest()
                .body(Result.fail(400, e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }

    /** 路径没匹配上（Spring 6.1+ 抛 NoResourceFoundException），别被兜底吞成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(Result.fail(404, "接口不存在: " + e.getResourcePath()));
    }

    /**
     * 模型服务（OpenAI 兼容接口）调用失败。
     * 这是上游依赖故障，不属于本服务的 500，所以映射成 502；
     * 限流单独给 429，方便前端做退避重试。
     */
    @ExceptionHandler(OpenAIException.class)
    public ResponseEntity<Result<Void>> handleAi(OpenAIException e) {
        if (e instanceof UnauthorizedException) {
            log.error("模型服务鉴权失败，请检查 API Key: {}", e.getMessage());
            return ResponseEntity.status(502)
                    .body(Result.fail(502, "模型服务鉴权失败，请检查 API Key"));
        }
        if (e instanceof RateLimitException) {
            log.warn("模型服务限流: {}", e.getMessage());
            return ResponseEntity.status(429)
                    .body(Result.fail(429, "调用过于频繁，请稍后重试"));
        }
        log.error("模型服务调用失败 [{}]: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity.status(502)
                .body(Result.fail(502, "模型服务调用失败，请稍后重试"));
    }

    /**
     * 客户端主动断开（流式接口最常见：用户关页面、断网）。
     * <p>
     * 这里必须返回 void：连接已经断了，再往响应体里写东西只会抛出二次异常。
     * 也不能记 ERROR——这不是服务端故障。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，停止推送: {}", e.getMessage());
    }

    /** 兜底：Spring 已能判定状态码的异常（405、类型不匹配等）保留原状态码，其余按 500 处理 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAll(Exception e) {
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            log.warn("请求异常 [{}]: {}", status, e.getMessage());
            return ResponseEntity.status(er.getStatusCode())
                    .body(Result.fail(status, e.getMessage() == null ? "请求不合法" : e.getMessage()));
        }
        log.error("请求失败", e);
        return ResponseEntity.internalServerError()
                .body(Result.fail(500, "系统繁忙，请稍后重试"));
    }
}
