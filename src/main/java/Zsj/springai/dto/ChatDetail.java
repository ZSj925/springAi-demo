package Zsj.springai.dto;

/**
 * 一次性返回的完整对话结果：模型回答 + token 用量 + 模型名 + 收尾原因 + 耗时。
 * <p>
 * 用 record 是因为它天生不可变、字段顺序固定（Jackson 会按声明顺序输出），
 * 正好适合做只读的响应 DTO。
 *
 * @param answer            模型回答正文
 * @param model             实际使用的模型名（如 deepseek-chat）
 * @param promptTokens      输入 token 数
 * @param completionTokens  输出 token 数
 * @param totalTokens       合计 token 数
 * @param finishReason      结束原因：stop=正常结束，length=被 maxTokens 截断
 * @param responseId        本次响应 id，排查问题时可以凭它向服务商查询
 * @param elapsedMs         整个调用耗时（毫秒）
 */
public record ChatDetail(
        String answer,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String finishReason,
        String responseId,
        long elapsedMs
) {
}
