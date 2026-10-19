package io.supportops.api;

import io.supportops.api.vo.ErrorResponse;
import io.supportops.knowledge.service.support.RerankingException;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** 将业务异常转换为稳定 HTTP 错误，不暴露服务商原始响应或密钥。 */
@RestControllerAdvice
public class ApiErrors {
    /** 重排序失败可定位但不暴露本地模型路径、文本或原生异常。 */
    @ExceptionHandler(RerankingException.class)
    ResponseEntity<ErrorResponse> reranking(RerankingException exception) {
        return ResponseEntity.status(503).body(new ErrorResponse(exception.getMessage()));
    }
    /** 保留明确声明的业务 HTTP 状态和受控错误码。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders());
        // 过滤器的速率限制和业务受理的活动/环境额度均给出同一有界等待提示。
        if (e.getStatusCode().value() == 429) {
            response.header("Retry-After", "60");
        }
        return response.body(new ErrorResponse(e.getReason() == null ? "REQUEST_FAILED" : e.getReason()));
    }

    /** 将缺失字段、类型错误及无效 JSON 统一映射为参数错误。 */
    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_INPUT"));
    }

    /** 将超出上传限制的请求映射为文件过大错误。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> oversized(Exception e) {
        return ResponseEntity.status(413).body(new ErrorResponse("FILE_TOO_LARGE"));
    }

    /** 拒绝不受支持的请求方式，并通过 Allow 响应头告知该路径支持的方法。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .headers(e.getHeaders())
                .body(new ErrorResponse("METHOD_NOT_ALLOWED"));
    }

    /** 已移除的接口路径不再匹配业务路由时，返回资源不存在而非内部故障。 */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(new ErrorResponse("RESOURCE_NOT_FOUND"));
    }

    /** 将未分类异常映射为固定内部错误，不返回原始异常消息。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_ERROR"));
    }

    /** 浏览器已断开流式响应时仅结束连接，不向失效的 SSE 通道再次写入 JSON。 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected(AsyncRequestNotUsableException exception) {
        // 任务继续由后台执行并持久化；新连接按游标读取，不重新受理诊断。
    }
}
