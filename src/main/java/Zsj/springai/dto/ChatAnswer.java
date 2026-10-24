package Zsj.springai.dto;

/**
 * 上下文对话的id和内容
 * @param conversationId
 * @param answer
 */

public record ChatAnswer(String conversationId, String answer) {}
