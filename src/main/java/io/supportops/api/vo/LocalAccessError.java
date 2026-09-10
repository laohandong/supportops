package io.supportops.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 本地访问边界拒绝请求时，Spring Boot 在 JSON 响应中返回的错误信息；浏览器也可能收到 HTML 错误页。 */
@Schema(
        name = "LocalAccessError",
        description = "本地访问边界拒绝请求时，Spring Boot 在 JSON 响应中返回的错误信息；浏览器也可能收到 HTML 错误页。")
public record LocalAccessError(
        @Schema(description = "错误发生时间。", format = "date-time") String timestamp,
        @Schema(description = "HTTP 状态码。", example = "403") int status,
        @Schema(description = "HTTP 状态短语。", example = "Forbidden") String error,
        @Schema(description = "被拒绝的请求路径。", example = "/api/status") String path) {}
