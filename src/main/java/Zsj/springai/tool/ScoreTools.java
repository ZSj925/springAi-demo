package Zsj.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScoreTools {

    private final JdbcTemplate jdbc;

    public ScoreTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(name = "query_score",
          description = "按学号查询学生各科成绩。当用户询问某学生的分数、成绩、排名时使用本工具。"
                      + "只接受 8 位数字学号，查不到时返回「未找到该学号」。")
    public String queryScore(@ToolParam(description = "8 位数字学号，例如 20230101") String studentId) {
        // 1) 入参校验自己来，别指望模型
        if (studentId == null || !studentId.matches("\\d{8}")) {
            return "学号格式不对，应为 8 位数字";
        }
        var rows = jdbc.queryForList("select subject, score from score where student_id = ?", studentId);
        return rows.isEmpty() ? "未找到该学号" : rows.toString();
    }

    @Tool(name = "now", description = "返回服务器当前时间。用户问「现在几点」「今天几号」时使用。")
    public String now() {
        return java.time.LocalDateTime.now().toString();
    }
}