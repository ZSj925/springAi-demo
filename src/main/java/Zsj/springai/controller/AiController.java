package Zsj.springai.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import Zsj.springai.common.Result;

/**
 * AI 能力接口（翻译等）
 */

@RestController
@RequestMapping("/ai")
@Validated //参数校验
public class AiController {

    /** 注入 AiConfig 里配置好的 ChatClient */
    private final ChatClient chatClient;

    public AiController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 中译英。
     * <p>
     * 两个关键点：
     * <ol>
     *   <li>{@code system(...)} 覆盖了 AiConfig 里默认的"Java 助教"人设，
     *       否则模型会用助教口吻回答，画风不对；</li>
     *   <li>{@code user(u -> u.text("...{text}").param("text", text))} 用的是模板变量，
     *       而不是字符串拼接——这样待翻译内容里就算有引号、花括号也不会破坏指令结构。</li>
     * </ol>
     *
     * @param text 待翻译的中文，1~2000 字
     * @return 英文译文
     */
    @GetMapping("/translate")
    public Result<String> translate(@RequestParam
                                    @NotBlank(message = "待翻译内容不能为空")
                                    @Size(max = 2000, message = "待翻译内容不能超过 2000 字") String text) {
        String english = chatClient.prompt()
                .system("你是一名专业翻译。只输出译文本身，不要解释、不要加引号、不要重复原文。")
                .user(u -> u.text("把下面的中文翻译成英文：\n{text}")
                        .param("text", text))
                .call()
                .content();
        return Result.ok(english);
    }
}
