package Zsj.springai.service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {

    private final SimpleVectorStore vectorStore;
    private final File storeFile;

    public KnowledgeService(SimpleVectorStore vectorStore,
                            @org.springframework.beans.factory.annotation.Value("${app.rag.store-file:./data/vector-store.json}") String path) {
        this.vectorStore = vectorStore;
        this.storeFile = new File(path);
    }

    /** 一条 ETL 流水线：DocumentReader -> DocumentTransformer -> DocumentWriter */
    public int ingest(Resource resource, String sourceName) throws IOException {
        // ① 抽取：Tika 认 docx/pdf/pptx/html/txt，中文 PDF 也能出字
        List<Document> docs = new TikaDocumentReader(resource).read();
        // 给每个文档打上来源，检索出来能溯源、也能按来源过滤
        docs.forEach(d -> d.getMetadata().put("source", sourceName));

        // ② 切块：默认 chunkSize=800 token，中文句子用 . ? ! \n 断，
        //    不换成中文标点的话，一整段中文会被硬切，语义断裂
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withPunctuationMarks(List.of('。', '？', '！', '；', '\n', '.', '?', '!'))
                .build();
        List<Document> chunks = splitter.apply(docs);

        // ③ 写入：add() 里会逐块调 EmbeddingModel 拿向量
        vectorStore.add(chunks);

        // ④ 落盘（内存版必须做）
        File dir = storeFile.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        vectorStore.save(storeFile);
        return chunks.size();
    }

    /** 纯检索接口：不经过大模型，用来调参/看召回质量 */
    public List<Document> search(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.5)
                .build());
    }
}