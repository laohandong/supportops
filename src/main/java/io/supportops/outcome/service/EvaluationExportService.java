package io.supportops.outcome.service;

import io.supportops.api.support.FixedCommandJson;
import io.supportops.outcome.dto.EvaluationExportRequest;
import io.supportops.source.vo.SourcePackage;
import io.swagger.v3.oas.annotations.media.Schema;

/** EvaluationExportService 业务边界，控制层只依赖此接口。 */
public interface EvaluationExportService {
    /** 原键创建或读取导出回执，锁定所见来源修订。 */
    Receipt create(String runId, EvaluationExportRequest input);

    /** 读取不可变来源包，不重新访问原诊断。 */
    SourcePackage get(String id);

    /** 可识别请求身份的非法正文保存终局拒绝，仅存摘要。 */
    Receipt reject(FixedCommandJson.Rejected rejected);

    /** 查询当前管理员的原请求结果。 */
    Receipt request(String id);

    /** 固定导出回执，失败时包为空。 */
    @Schema(description = "来源导出固定回执")
    public record Receipt(
            @Schema(description = "固定请求UUID") String requestId,
            @Schema(description = "原HTTP状态") int httpStatus,
            @Schema(description = "失败代码") String errorCode,
            @Schema(description = "导出包UUID") String exportId,
            @Schema(description = "不可变来源包") SourcePackage sourcePackage) {}
}
