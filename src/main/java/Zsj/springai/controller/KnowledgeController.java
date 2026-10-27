package Zsj.springai.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import Zsj.springai.common.Result;
import Zsj.springai.service.KnowledgeService;

@RestController
@RequestMapping("/ai/kb")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 上传一个文件入库 */
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        int chunks = knowledgeService.ingest(file.getResource(), file.getOriginalFilename());
        return Result.ok("入库完成，共 " + chunks + " 块");
    }

    /** 只看召回，不看大模型 —— 排查"答不对"是检索的锅还是模型的锅 */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "5") int topK) {
        List<Document> docs = knowledgeService.search(q, topK);
        return Result.ok(docs.stream()
                .map(d -> Map.<String, Object>of(
                        "score", d.getScore(),               // 2.0 里文本是 getText()，不再是 getContent()
                        "source", d.getMetadata().getOrDefault("source", "?"),
                        "text", d.getText()))
                .toList());
    }
}