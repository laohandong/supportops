package io.supportops.knowledge.controller;

import io.supportops.api.vo.ErrorResponse;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 知识业务只返回服务端定义的短错误码，禁止返回 SQL、单元格值或外部响应。 */
@Order(-10)
@RestControllerAdvice(
        assignableTypes = {KnowledgeController.class, DocumentLibraryController.class})
public class KnowledgeErrors {
    /** 保留可定位的 SQL 策略、解析参数和重试状态错误。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> input(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(KnowledgeValues.error(exception)));
    }

    /** 明确报告外部存储或检索不可用，不静默切换检索模式。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(503).body(new ErrorResponse(KnowledgeValues.error(exception)));
    }
}
