package Zsj.springai.dto;

/**
 * 结构化输出的目标对象：让模型直接按这个结构产出 JSON，再反序列化成对象。
 * <p>
 * 用 record 的理由：字段即结构，Jackson 能按声明顺序序列化，
 * 而且 Spring AI 的 BeanOutputConverter 会根据它的字段自动生成 JSON Schema 指令。
 * <p>
 * 注意字段类型用包装类（Integer 而非 int）：
 * 模型偶尔会漏字段或返回 null，用基本类型会在反序列化时直接抛异常。
 *
 * @param title    电影名
 * @param director 导演
 * @param year     上映年份
 * @param genre    类型（科幻/剧情/动画…）
 * @param reason   推荐理由，一句话
 */
public record Movie(
        String title,
        String director,
        Integer year,
        String genre,
        String reason
) {
}
