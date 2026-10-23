package Zsj.springai.common;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.ToString;

/**
 * 统一响应结构。所有接口（成功或失败）都返回它，前端只需解析一次。
 *
 * <pre>
 * 成功：{ "code": 200, "message": "success", "data": "……" }
 * 失败：{ "code": 400, "message": "问题不能为空", "data": null }
 * </pre>
 * <p>
 * 约定：code 与 HTTP 状态码保持一致（200/400/404/405/500），
 * 这样 HTTP 层和业务层看的是同一个数字，不会出现"HTTP 200 但业务失败"的歧义。
 *
 * @param <T> 业务数据类型
 */
@Getter   // 自动生成 getCode() / getMessage() / getData()，Jackson 靠 getter 识别属性
@ToString // 生成可读的 toString，打日志时不会只看到 类名@哈希码
@JsonPropertyOrder({"code", "message", "data"}) // Jackson：固定 JSON 字段顺序（否则是字母序 code/data/message）
public class Result<T> {

    /**
     * 业务状态码，与 HTTP 状态码一致
     */
    private final int code;

    /**
     * 提示信息，成功固定为 success，失败为具体原因
     */
    private final String message;

    /**
     * 业务数据，失败时为 null
     */
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功并携带数据
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 成功但不返回数据（如删除、更新类接口）
     */
    public static Result<Void> ok() {
        return new Result<>(200, "success", null);
    }

    /**
     * 失败，code 直接传 HTTP 状态码
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
