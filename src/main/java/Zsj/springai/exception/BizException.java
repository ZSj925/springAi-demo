package Zsj.springai.exception;

/**
 * 业务异常：由代码主动抛出，携带一个明确的状态码。
 * <p>
 * 用它而不是直接返回 Result.fail，是为了让"业务规则不满足"和"技术故障"
 * 在异常处理器里走不同的分支、打不同级别的日志。
 */
public class BizException extends RuntimeException {

    /** 业务状态码，建议直接用 HTTP 状态码（如 422） */
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
