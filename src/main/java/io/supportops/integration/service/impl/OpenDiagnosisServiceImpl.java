package io.supportops.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.integration.dto.OpenDiagnosisCommand;
import io.supportops.integration.entity.IntegrationRequestEntity;
import io.supportops.integration.mapper.IntegrationRequestMapper;
import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.service.OpenDiagnosisService;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.integration.vo.OpenViews;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.TargetView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 单实例原子受理及白名单事件投影；读取操作绝不重新执行诊断。 */
@Service
public class OpenDiagnosisServiceImpl implements OpenDiagnosisService {
    private static final Set<String> EVIDENCE = Set.of("SOURCE_EVIDENCE", "KNOWLEDGE", "SQL_RESULT");
    private final IntegrationRequestMapper requests;
    private final ApplicationService applications;
    private final DiagnosticTargetService targets;
    private final ProjectService projects;
    private final RunService runs;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    /** 版本化摘要的固定字段顺序，不包含 requestId 和客户端所有者。 */
    private record Canonical(int schemaVersion, String environmentId, String question, boolean lexicalOnly,
            OpenDiagnosisCommand.ExternalReference externalReference) {}
    /** 注入应用授权、目标及任务业务边界。 */
    public OpenDiagnosisServiceImpl(IntegrationRequestMapper requests, ApplicationService applications,
            DiagnosticTargetService targets, ProjectService projects, RunService runs, TransactionTemplate transactions, ObjectMapper json) {
        this.requests = requests; this.applications = applications; this.targets = targets; this.projects = projects;
        this.runs = runs; this.transactions = transactions; this.json = json;
    }
    /** 目标名称可公开，连接地址及凭据不能进入响应。 */
    @Override public List<TargetView> environments(ApplicationPrincipal actor) {
        applications.authorize(actor, "READ", null);
        List<TargetView> result = new ArrayList<>();
        for (TargetView target : projects.targets(null, true)) {
            try { applications.authorize(actor, "READ", target.environmentId()); result.add(target); }
            catch (org.springframework.web.server.ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 403) { throw exception; }
            }
        }
        return List.copyOf(result);
    }
    /** 短事务包含 run、目标和回执；同键竞争不会创建第二个任务。 */
    @Override public synchronized OpenViews.Receipt submit(ApplicationPrincipal actor, OpenDiagnosisCommand input) {
        String requestId = IntegrationInputs.uuid(input.requestId());
        String environment = IntegrationInputs.uuid(input.environmentId());
        String question = trimAscii(IntegrationInputs.text(input.question(), 6000));
        IntegrationInputs.text(question, 6000);
        if (input.externalReference() != null) {
            if (!Set.of("TICKET", "INCIDENT", "OTHER").contains(input.externalReference().type())) {
                throw IntegrationInputs.error(400, "INVALID_EXTERNAL_REFERENCE");
            }
            IntegrationInputs.text(input.externalReference().id(), 128);
        }
        applications.authorize(actor, "DIAGNOSE", environment);
        String hash = IntegrationInputs.digest(encode(new Canonical(1, environment, question, input.lexicalOnly(), input.externalReference())));
        IntegrationRequestEntity existing = find(actor, requestId);
        if (existing != null) {
            if (!hash.equals(existing.getPayloadHash())) { throw IntegrationInputs.error(409, "REQUEST_CONFLICT"); }
            return receipt(actor, existing, true);
        }
        DiagnosticScope scope = targets.application(environment, actor);
        return transactions.execute(transaction -> {
            String runId = UUID.randomUUID().toString();
            runs.startApplication(runId, question, input.lexicalOnly(), actor, scope);
            IntegrationRequestEntity value = new IntegrationRequestEntity(); value.setId(UUID.randomUUID().toString());
            value.setApplicationId(actor.applicationId()); value.setRequestId(requestId); value.setSchemaVersion(1);
            value.setPayloadHash(hash); value.setRunId(runId); value.setAcceptedAt(Instant.now().toString());
            value.setExternalReferenceJson(input.externalReference() == null ? null : encode(input.externalReference()));
            requests.insert(value);
            return new OpenViews.Receipt(requestId, runId, "QUEUED", value.getAcceptedAt(), scope.target(), false,
                    path(runId), path(runId) + "/events", path(runId) + "/result");
        });
    }
    /** UNKNOWN 时查询原请求，404 只允许客户端重发同一键和正文。 */
    @Override public OpenViews.Receipt request(ApplicationPrincipal actor, String requestId) {
        applications.authorize(actor, "READ", null);
        IntegrationRequestEntity value = find(actor, IntegrationInputs.uuid(requestId));
        if (value == null) { throw IntegrationInputs.error(404, "REQUEST_NOT_FOUND"); }
        return receipt(actor, value, true);
    }
    /** 相同应用且当前环境授权成立时才能读取。 */
    @Override public OpenViews.Diagnosis diagnosis(ApplicationPrincipal actor, String runId) {
        String id = IntegrationInputs.uuid(runId);
        DiagnosisRun run = runs.getApplication(id, actor);
        IntegrationRequestEntity request = requests.selectOne(Wrappers.<IntegrationRequestEntity>lambdaQuery()
                .eq(IntegrationRequestEntity::getApplicationId, actor.applicationId()).eq(IntegrationRequestEntity::getRunId, id));
        if (request == null) { throw IntegrationInputs.error(404, "RUN_NOT_FOUND"); }
        DiagnosticScope scope = targets.snapshot(id);
        return new OpenViews.Diagnosis(id, request.getRequestId(), run.status(), scope.target(), run.answer(), run.errorCode(),
                new OpenViews.Usage(run.inputTokens(), run.outputTokens(), runs.usageReportedApplication(id, actor)),
                run.createdAt(), run.finishedAt(), run.elapsedMs());
    }
    /** 游标按实际读取记录推进；内部推理和工具参数不进入公开页。 */
    @Override public OpenViews.EventPage events(ApplicationPrincipal actor, String runId, long after, int limit) {
        String id = IntegrationInputs.uuid(runId);
        List<DiagnosisEvent> original = runs.eventsApplication(id, actor, after, limit);
        List<OpenViews.Event> result = new ArrayList<>(); long cursor = after; int total = 256;
        for (DiagnosisEvent event : original) {
            JsonNode safe = project(event);
            String reference = EVIDENCE.contains(event.kind()) ? path(id) + "/evidence/" + event.id() : null;
            int bytes = size(safe);
            boolean truncated = bytes > 14000;
            if (truncated) {
                ObjectNode summary = json.createObjectNode(); summary.put("summary", "事实正文超出事件上限，请读取持久化证据或任务回答");
                summary.put("originalBytes", bytes); safe = summary;
            }
            OpenViews.Event projected = new OpenViews.Event(event.id(), event.kind(), safe, truncated, reference, event.createdAt());
            int eventBytes = size(projected) + 1;
            if (total + eventBytes > 1024 * 1024) { break; }
            total += eventBytes; result.add(projected); cursor = event.id();
        }
        return new OpenViews.EventPage(List.copyOf(result), cursor, result.size() < original.size() || original.size() == limit);
    }
    /** 只接受公开事实事件，严格匹配 run 和 eventId；从不访问源系统。 */
    @Override public OpenViews.Evidence evidence(ApplicationPrincipal actor, String runId, long eventId) {
        if (eventId < 1) { throw IntegrationInputs.error(400, "INVALID_EVIDENCE_ID"); }
        List<DiagnosisEvent> records = runs.eventsApplication(IntegrationInputs.uuid(runId), actor, eventId - 1, 1);
        if (records.isEmpty() || records.getFirst().id() != eventId || !EVIDENCE.contains(records.getFirst().kind())) {
            throw IntegrationInputs.error(404, "EVIDENCE_NOT_FOUND");
        }
        DiagnosisEvent event = records.getFirst();
        JsonNode content = project(event);
        OpenViews.Evidence value = new OpenViews.Evidence(eventId, event.kind(), content, event.createdAt());
        if (size(value) > 2 * 1024 * 1024) { throw IntegrationInputs.error(413, "EVIDENCE_TOO_LARGE"); }
        return value;
    }
    /** 已失败、取消或中断仍属于可读取终态，不将结果包装为成功解决。 */
    @Override public OpenViews.Result result(ApplicationPrincipal actor, String runId) {
        OpenViews.Diagnosis diagnosis = diagnosis(actor, runId);
        if (Set.of("QUEUED", "RUNNING").contains(diagnosis.status())) { throw IntegrationInputs.error(409, "RESULT_NOT_READY"); }
        return new OpenViews.Result(diagnosis, runs.evidenceIdsApplication(runId, actor).stream()
                .map(id -> path(runId) + "/evidence/" + id).toList());
    }
    /** 重复取消保留原终态及累计用量。 */
    @Override public OpenViews.Diagnosis cancel(ApplicationPrincipal actor, String runId) {
        runs.cancelApplication(IntegrationInputs.uuid(runId), actor); return diagnosis(actor, runId);
    }
    /** 按应用和固定 UUID 查找，不允许客户端提交其他应用标识。 */
    private IntegrationRequestEntity find(ApplicationPrincipal actor, String requestId) {
        return requests.selectOne(Wrappers.<IntegrationRequestEntity>lambdaQuery().eq(IntegrationRequestEntity::getApplicationId, actor.applicationId())
                .eq(IntegrationRequestEntity::getRequestId, requestId));
    }
    /** 只读重放不占用新的诊断容量。 */
    private OpenViews.Receipt receipt(ApplicationPrincipal actor, IntegrationRequestEntity value, boolean replayed) {
        DiagnosisRun run = runs.getApplication(value.getRunId(), actor);
        return new OpenViews.Receipt(value.getRequestId(), value.getRunId(), run.status(), value.getAcceptedAt(),
                targets.snapshot(run.id()).target(), replayed, path(run.id()), path(run.id()) + "/events", path(run.id()) + "/result");
    }
    /** 明确白名单；私有执行位置和原始工具参数仅留在后台事实中。 */
    private JsonNode project(DiagnosisEvent event) {
        JsonNode value = json.valueToTree(event.content());
        if (EVIDENCE.contains(event.kind()) || Set.of("ANSWER_DELTA", "USAGE").contains(event.kind())) { return value; }
        ObjectNode safe = json.createObjectNode();
        if ("TOOL_CALL".equals(event.kind())) { safe.put("tool", value.path("tool").asText()); }
        else if ("ERROR".equals(event.kind())) { safe.put("error", "DIAGNOSIS_EXECUTION_ERROR"); }
        else { safe.put("summary", "内部执行事件"); }
        return safe;
    }
    /** 只修剪规范指定的四种 ASCII 边缘空白。 */
    private String trimAscii(String value) {
        int start = 0; int end = value.length();
        while (start < end && "\t\n\r ".indexOf(value.charAt(start)) >= 0) { start++; }
        while (end > start && "\t\n\r ".indexOf(value.charAt(end - 1)) >= 0) { end--; }
        return value.substring(start, end);
    }
    /** 固定版本路径，不包含访问秘密。 */
    private String path(String runId) { return "/open-api/v1/diagnoses/" + runId; }
    /** 序列化固定顺序业务模型，用于摘要与边界检查。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("OPEN_API_ENCODING_FAILED"); }
    }
    /** 对实际 UTF-8 JSON 响应计数。 */
    private int size(Object value) {
        try { return json.writeValueAsBytes(value).length; } catch (Exception exception) { throw new IllegalStateException("OPEN_API_ENCODING_FAILED"); }
    }
}
