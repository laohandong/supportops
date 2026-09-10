package io.supportops.demo.vo;

import com.fasterxml.jackson.databind.JsonNode;

/** MCP 健康检查事实；保留下游 HTTP 状态和原始响应正文。 */
public record DemoHealth(int httpStatus, JsonNode body, String observedAt) {}
