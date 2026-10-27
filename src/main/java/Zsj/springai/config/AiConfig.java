package Zsj.springai.config;

import Zsj.springai.tool.ScoreTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * 自定义 ChatClient：带上默认系统提示词。
     * <p>
     * 提示词从 application.yaml 的 app.ai.system-prompt 读取，
     * 改提示词不用改代码、不用重新编译。没配时用注解里的默认值兜底。
     * <p>
     * 注意：{@code QuestionAnswerAdvisor} 是 RagConfig 里配的 RAG 顾问，
     * 挂成 defaultAdvisor 之后，所有走这个 chatClient 的请求都会先检索知识库。
     *
     * @param builder               Spring AI 自动配置好的构建器
     * @param systemPrompt          系统提示词
     * @param questionAnswerAdvisor RAG 顾问（检索 + 拼上下文）
     * @return 配置好的 ChatClient，容器里唯一的 ChatClient bean
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 @Value("${app.ai.system-prompt:你是一个简洁的 Java 助教}") String systemPrompt,
                                 QuestionAnswerAdvisor questionAnswerAdvisor,
                                 ScoreTools scoreTools) {
        return builder
                .defaultSystem(systemPrompt)//系统提示词
                .defaultAdvisors(questionAnswerAdvisor)//RAG
                .defaultTools(scoreTools)//调用工具
                .build();
    }

    /**
     * 上下文配置
     * @param chatMemory
     * @return
     */
    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
