package Zsj.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
     *
     * @param builder      Spring AI 自动配置好的构建器
     * @param systemPrompt 系统提示词
     * @return 配置好的 ChatClient，容器里唯一的 ChatClient bean
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 @Value("${app.ai.system-prompt:你是一个简洁的 Java 助教}") String systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)//系统提示词
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
