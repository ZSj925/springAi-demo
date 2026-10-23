package Zsj.springai.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import Zsj.springai.common.Result;
import Zsj.springai.dto.ChatDetail;
import Zsj.springai.dto.Movie;
import Zsj.springai.exception.BizException;

/**
 * 用户相关接口
 */

@RestController
@RequestMapping("/user")
@Validated //参数校验
public class userController {

    /** 注入 AiConfig 里配置好的那个 ChatClient（容器里唯一的 ChatClient bean） */
    private final ChatClient chatClient;

    public userController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 对话输出（非流式输出，输出纯文本）
     *
     * @param q 问题，1~2000 字
     * @return 模型回答
     */
    @GetMapping("/chat")
    public Result<String> chat(@RequestParam
                               @NotBlank(message = "问题不能为空")
                               @Size(max = 2000, message = "问题不能超过 2000 字") String q) {
        String answer = chatClient.prompt().user(q).call().content();
        return Result.ok(answer);
    }

    /**
     * 对话输出（流式输出，逐段返回纯文本）
     * <p>
     * 返回 Flux&lt;String&gt;：模型每生成一段就立刻推给客户端，前端可以做打字机效果。
     * produces 指定 text/plain，所以响应体是裸文本，没有 SSE 的 "data:" 前缀。
     * <p>
     * 注意：不能写成 Result&lt;Flux&lt;String&gt;&gt;。流是一个"边生成边写"的过程，
     * 而 Result 是"攒齐后一次性序列化"的对象，两者互斥；且 produces=text/plain 时
     * Jackson 转换器会被过滤掉，包装类没有转换器可写，会抛 HttpMessageNotWritableException。
     *
     * @param q 问题，1~2000 字
     * @return 文本片段流
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> chatStream(@RequestParam
                                   @NotBlank(message = "问题不能为空")
                                   @Size(max = 2000, message = "问题不能超过 2000 字") String q) {
        return chatClient.prompt().user(q).stream().content();
    }

    /**
     * 对话输出（完整响应：正文 + token 用量 + 模型名 + 收尾原因 + 耗时）
     *
     * <p>为什么需要这个方法：{@code content()} 只把正文掏出来，其余元数据（token 用量、
     * 实际模型、结束原因、响应 id）全部丢弃；{@code chatResponse()} 返回的是完整信封，
     * 需要什么自己取。计费、监控、排查线上问题都依赖这些元数据。
     *
     * <p>与另外两个对话接口的关系：
     * <ul>
     *   <li>{@code /chat}        → {@code call().content()}       → 只要文字</li>
     *   <li>{@code /chat/stream} → {@code stream().content()}     → 边生成边推</li>
     *   <li>{@code /chat/detail} → {@code call().chatResponse()}  → 要元数据齐全（本方法）</li>
     * </ul>
     *
     * @param q 问题，1~2000 字
     * @return 统一响应结构 Result，data 为 ChatDetail
     */
    @GetMapping("/chat/detail")
    public Result<ChatDetail> chatDetail(@RequestParam
                                         @NotBlank(message = "问题不能为空")
                                         @Size(max = 2000, message = "问题不能超过 2000 字") String q) {

        // ① 计时开始：包住"一次完整的模型调用"，含建连、排队、推理、回传的全部时间
        long start = System.currentTimeMillis();

        // ② 核心调用链，逐段拆开看：
        //    chatClient.prompt()  → 拿到请求构造器（此时还没发请求）
        //    .user(q)             → 追加一条用户消息，相当于 messages[{role:user, content:q}]
        //    .call()              → 同步发请求；阻塞当前 Tomcat 线程，直到拿到完整响应
        //    .chatResponse()      → 返回原始信封；对比 .content() 只返回 output 里的文本
        ChatResponse response = chatClient.prompt().user(q).call().chatResponse();

        // ③ 计时结束。这个耗时不等于"生成速度"，它包含网络往返和排队
        long elapsedMs = System.currentTimeMillis() - start;

        // ④ 从信封里取出两棵子树：
        //    response
        //      ├─ result[]（可能有多个候选，这里只取第一个）
        //      │    ├─ output.text
        //      │    └─ metadata.finishReason
        //      └─ metadata（id、model、usage、限流信息）
        Generation result = response.getResult();

        Usage usage = response.getMetadata().getUsage();

        // ⑤ usage 做空值兜底：不同服务商、以及某些异常场景下 usage 可能是 null，
        //    直接调用会 NPE。这里统一成"取不到就是 null"，由调用方自行判断。
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        // totalTokens 自己相加，不用 usage.getTotalTokens()：
        // 它的默认实现就是两者相加，遇到 null 会抛 NPE，这里自己算更可控
        Integer totalTokens = (promptTokens == null || completionTokens == null)
                ? null : promptTokens + completionTokens;

        // ⑥ 组装成对外返回的 DTO（record，字段声明顺序就是 JSON 输出顺序）
        ChatDetail detail = new ChatDetail(
                result.getOutput().getText(),           // 正文
                response.getMetadata().getModel(),      // 实际模型名，可能是别名映射后的结果
                promptTokens,                           // 输入 token
                completionTokens,                       // 输出 token
                totalTokens,                            // 合计
                result.getMetadata().getFinishReason(), // STOP=正常结束，LENGTH=被 maxTokens 截断
                response.getMetadata().getId(),         // 响应 id，排查问题时凭它向服务商查询
                elapsedMs);                             // 端到端耗时

        // ⑦ 套上统一响应结构，code=200、message=success
        return Result.ok(detail);
    }

    /**
     * 对话输出（结构化输出：模型直接产出对象，而不是一段文字）
     * <p>
     * {@code .entity(Movie.class)} 背后做了三件事：
     * <ol>
     *   <li>用 BeanOutputConverter 根据 Movie 的字段生成一段 JSON Schema 说明，追加到 prompt；</li>
     *   <li>照常发请求（同步、阻塞）；</li>
     *   <li>把模型返回的文本当 JSON 解析成 Movie 对象。</li>
     * </ol>
     * 注意：解析成功不等于业务上拿到了有效数据——模型可能拒答并把字段填成"无法确定"，
     * 所以下面要再做一次业务校验，无效结果按 422 返回，不能让调用方以为是成功。
     *
     * @param q 想推荐什么，例如"推荐一部科幻电影"
     * @return 填好的 Movie 对象
     */
    @GetMapping("/chat/entity")
    public Result<Movie> chatEntity(@RequestParam
                                    @NotBlank(message = "问题不能为空")
                                    @Size(max = 2000, message = "问题不能超过 2000 字") String q) {
        Movie movie = chatClient.prompt()
                .user(q)
                .call()
                .entity(Movie.class);

        // 业务校验：真实电影一定有片名，且年份是个合理正数；
        // 模型拒答时常见形态是 title="无法确定" 且 year=0
        boolean invalid = movie == null
                || movie.title() == null || movie.title().isBlank()
                || movie.year() == null || movie.year() <= 0;
        if (invalid) {
            throw new BizException(422, "模型未能按预期结构返回，请换个问法再试");
        }
        return Result.ok(movie);
    }

}
