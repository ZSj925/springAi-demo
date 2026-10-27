package Zsj.springai.config;

import java.io.File;
import java.util.List;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    /**
     * 向量库。SimpleVectorStore 是纯内存 + JSON 文件，只适合练手/单机。
     * 一定要 save/load，否则一重启库就空了，模型答得一脸茫然。
     */
    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel,
                                         @Value("${app.rag.store-file:./data/vector-store.json}") String path) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(path);
        if (file.exists()) {
            store.load(file);        // 启动时把上次的向量读回来
        }
        return store;
    }

    /**
     * RAG 的核心：每次带这个 advisor 的请求，都会先拿用户问题去向量库里捞 topK 块，
     * 再按默认模板拼成 "Context information is below ... 问题" 交给模型。
     * 检索为空时，默认模板会明确要求模型回答"我不知道"，而不是瞎编。
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(5)                    // 捞几块：太少答不全，太多噪声大、费 token
                        .similarityThreshold(0.5)   // 余弦相似度下限，低于它的直接丢
                        .build())
                .build();
    }
}