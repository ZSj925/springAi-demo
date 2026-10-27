package Zsj.springai.exception;

import com.openai.errors.OpenAIException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import Zsj.springai.common.Result;
import Zsj.springai.dto.Movie;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 + 全局业务校验器。
 *
 * <p>一个类干两件事：
 * <ol>
 *   <li>实现 {@code @ExceptionHandler}：把异常统一转成 Result；</li>
 *   <li>实现 {@link ResponseBodyAdvice}：在响应写出前检查 Result 里的 data，
 *       不合法就抛 BizException，再回到第 1 步统一处理。</li>
 * </ol>
 *
 * <p>为什么要用 ResponseBodyAdvice：{@code @ExceptionHandler} 只在"已经抛了异常"之后才执行，
 * 校验逻辑写在那里永远不会被触发；而 ResponseBodyAdvice 是在 controller 正常返回之后、
 * JSON 写出之前被调用，正好是"集中校验"的位置。这样 controller 里只剩业务代码。
 *
 * <p>处理顺序（Spring 自动按"最具体"匹配，不用手写）：
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
public class GlobalExceptionHandler implements ResponseBodyAdvice<Object> {

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

    // ==================================================================
    //  以下是全局业务校验：响应写出前统一检查 data，controller 里不再写 if
    // ==================================================================

    /** 只拦截返回类型是 Result 的响应；流式（Flux）、错误响应等一律放过 */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    /**
     * controller 返回之后、JSON 写出之前执行。
     * 校验不通过就抛 BizException，自然流转到上面的 handleBiz，统一变成 422。
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {

        if (!(body instanceof Result<?> result)) {
            return body;
        }

        Object data = result.getData();

        // 单个对象：Movie
        if (data instanceof Movie movie) {
            requireValidMovie(movie);
            return body;
        }

        // 列表：List<Movie>
        // 注意 list.isEmpty() 要单独判：allMatch 在空列表上返回 true（vacuous truth），
        // 会把"本来就没数据"的 List<Map> 之类误当成 List<Movie>，直接抛 422。
        // 所以空列表时改为看方法声明的泛型到底是不是 List<Movie>。
        if (data instanceof List<?> list) {
            boolean looksLikeMovieList = list.isEmpty()
                    ? isMovieReturnType(returnType)
                    : list.stream().allMatch(Movie.class::isInstance);
            if (looksLikeMovieList) {
                List<Movie> valid = ((List<Movie>) list).stream()
                        .filter(this::isValidMovie)
                        .toList();
                if (valid.isEmpty()) {
                    throw new BizException(422, "模型未能按预期结构返回，请换个问法再试");
                }
                return Result.ok(valid);
            }
        }

        // data 为 null 但方法声明要的就是 Movie / List——同样视为失败
        if (data == null && isMovieReturnType(returnType)) {
            throw new BizException(422, "模型未能按预期结构返回，请换个问法再试");
        }

        return body;
    }

    /** 单个对象校验；不合法直接抛，交给 handleBiz */
    private void requireValidMovie(Movie movie) {
        if (!isValidMovie(movie)) {
            throw new BizException(422, "模型未能按预期结构返回，请换个问法再试");
        }
    }

    /**
     * 业务规则：真实电影一定有片名、有导演、年份是合理正数。
     * <p>
     * 模型拒答有两种形态：① 字段全空（title="", year=0）；② 硬凑一条假数据，
     * 把导演/类型填成 unknown、未知、无法确定 这类占位词。两种都要拦住。
     */
    private boolean isValidMovie(Movie movie) {
        if (movie == null) {
            return false;
        }
        if (isBlank(movie.title()) || movie.year() == null || movie.year() <= 0) {
            return false;
        }
        return isMeaningful(movie.director()) && isMeaningful(movie.genre());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 过滤掉 unknown / N/A / 无法确定 这类"假装有值"的占位词 */
    private boolean isMeaningful(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return !PLACEHOLDER_WORDS.contains(normalized);
    }

    private static final Set<String> PLACEHOLDER_WORDS =
            Set.of("unknown", "n/a", "na", "null", "none", "未知", "无法确定", "暂无", "不详");

    /** 判断方法声明的 Result 里的数据类型是不是 Movie 或 List&lt;Movie&gt; */
    private boolean isMovieReturnType(MethodParameter returnType) {
        Type type = returnType.getGenericParameterType();
        if (!(type instanceof ParameterizedType resultType)) {
            return false;
        }
        Type dataType = resultType.getActualTypeArguments()[0];
        if (dataType instanceof Class<?> clazz) {
            return Movie.class.isAssignableFrom(clazz);
        }
        if (dataType instanceof ParameterizedType listType
                && listType.getRawType() instanceof Class<?> raw
                && List.class.isAssignableFrom(raw)
                && listType.getActualTypeArguments()[0] instanceof Class<?> element) {
            return Movie.class.isAssignableFrom(element);
        }
        return false;
    }
}
