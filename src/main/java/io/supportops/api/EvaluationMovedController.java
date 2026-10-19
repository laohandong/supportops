package io.supportops.api;

import io.supportops.api.vo.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 旧评测接口保留鉴权并返回迁移状态；不读取旧表，也不转发任何写请求。 */
@RestController
@Tag(name="评测迁移兼容", description="仅管理员；评测功能已迁往独立 AgentEval")
public final class EvaluationMovedController {
    /** 旧读取入口明确失效，避免空列表被误认为旧历史已经删除。 */
    @GetMapping({"/api/evaluations", "/api/evaluations/**"})
    @Operation(summary="旧评测读取入口已迁移", description="所有旧评测路径返回410，不查询旧评测数据")
    @ApiResponse(responseCode="410", description="EVALUATION_MOVED；在 AgentEval 中查看迁移历史")
    public ResponseEntity<ErrorResponse> read() {
        return moved();
    }

    /** 旧写入不产生任何数据变更，也不使用跨域重定向重放请求。 */
    @PostMapping({"/api/evaluations", "/api/evaluations/**"})
    @Operation(summary="旧评测写入口已关闭", description="所有旧写请求返回410，不转发请求，不执行数据库写入")
    @ApiResponse(responseCode="410", description="EVALUATION_MOVED；POST 不重定向或转发")
    public ResponseEntity<ErrorResponse> write() {
        return moved();
    }

    /** GET 与 POST 使用同一固定、无敏感信息的兼容错误模型。 */
    private ResponseEntity<ErrorResponse> moved() {
        return ResponseEntity.status(410).body(new ErrorResponse("EVALUATION_MOVED"));
    }
}
