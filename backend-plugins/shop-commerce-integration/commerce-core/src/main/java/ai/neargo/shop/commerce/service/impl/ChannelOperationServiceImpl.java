package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.OperationContracts.Attempt;
import ai.neargo.shop.commerce.dto.OperationContracts.Query;
import ai.neargo.shop.commerce.dto.OperationContracts.Schedule;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** 查询不触发外部动作；授权URL在消费state或到期后立即裁掉。 */
public class ChannelOperationServiceImpl implements ChannelOperationService {
    private final OperationMapper operations;
    private final OperationAttemptMapper attempts;
    private final CommerceCommands commands;
    private final SyncRunMapper runs;
    private final ResourceMappingMapper mappings;

    public ChannelOperationServiceImpl(
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands) {
        this(operations, attempts, commands, null);
    }

    public ChannelOperationServiceImpl(
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            SyncRunMapper runs) {
        this(operations, attempts, commands, runs, null);
    }

    public ChannelOperationServiceImpl(
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            SyncRunMapper runs,
            ResourceMappingMapper mappings) {
        this.operations = operations;
        this.attempts = attempts;
        this.commands = commands;
        this.runs = runs;
        this.mappings = mappings;
    }

    @Override
    public OperationVO getOperation(String entity, String operationNo) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(operationNo, 32);
        return commands.query(
                () -> {
                    var op =
                            operations.selectOne(
                                    Wrappers.<CimOperation>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("operation_no", operationNo));
                    if (op == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    var attempt =
                            attempts.selectOne(
                                    Wrappers.<CimOperationAttempt>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("operation_no", operationNo)
                                            .eq("attempt_no", op.getCurrentAttemptNo()));
                    return view(op, attempt);
                });
    }

    @Override
    public PageData<OperationVO> listOperations(String entity, String store, Query request) {
        CommerceValidation.text(entity, 32);
        if (store != null) CommerceValidation.text(store, 32);
        if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        long offset = CommerceValidation.offset(request.page(), request.size());
        if (request.type() != null) CommerceValidation.text(request.type(), 48);
        if (request.status() != null) CommerceValidation.text(request.status(), 24);
        return commands.query(
                () -> {
                    var q =
                            Wrappers.<CimOperation>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq(request.type() != null, "operation_type", request.type())
                                    .eq(request.status() != null, "status", request.status());
                    if (store == null) q.isNull("store_no");
                    else q.eq("store_no", store);
                    long total = operations.selectCount(q);
                    var data =
                            operations
                                    .selectList(
                                            q.orderByDesc("created_at", "id")
                                                    .last(
                                                            "LIMIT "
                                                                    + request.size()
                                                                    + " OFFSET "
                                                                    + offset))
                                    .stream()
                                    .map(op -> view(op, null))
                                    .toList();
                    return PageData.of(data, total, request.page(), request.size());
                });
    }

    @Override
    public PageData<Attempt> listAttempts(
            String entity, String store, String number, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    scoped(entity, store, number, false);
                    var q =
                            Wrappers.<CimOperationAttempt>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("operation_no", number);
                    long total = attempts.selectCount(q);
                    var rows =
                            attempts.selectList(
                                    q.orderByAsc("attempt_no", "id")
                                            .last("LIMIT " + size + " OFFSET " + offset));
                    return PageData.of(
                            rows.stream()
                                    .map(
                                            a ->
                                                    new Attempt(
                                                            a.getAttemptRef(),
                                                            a.getAttemptNo(),
                                                            a.getAttemptKind(),
                                                            a.getOutcome(),
                                                            a.getErrorCode(),
                                                            a.getResponseRef(),
                                                            a.getStartedAt()
                                                                    .toInstant(ZoneOffset.UTC),
                                                            a.getFinishedAt() == null
                                                                    ? null
                                                                    : a.getFinishedAt()
                                                                            .toInstant(
                                                                                    ZoneOffset
                                                                                            .UTC)))
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    @Override
    public OperationVO scheduleRecovery(
            String entity,
            String store,
            String number,
            String actor,
            Schedule request,
            String key,
            boolean refreshOnly) {
        if (request == null
                || request.version() == null
                || request.version() < 0
                || request.reason() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(request.reason(), 256);
        // Read the immutable routing fields before the journal transaction. Within it, child rows
        // lock before the operation,
        // matching worker lock order and avoiding SERIALIZABLE read-lock upgrades from operation ->
        // run/mapping.
        var before = commands.query(() -> scoped(entity, store, number, false));
        return commands.execute(
                entity,
                store,
                actor,
                (refreshOnly ? "REFRESH_OPERATION:" : "RECOVER_OPERATION:") + number,
                key,
                request,
                OperationVO.class,
                () -> {
                    // Scheduling cannot reset a status, release a barrier or create another send
                    // attempt.
                    if ("CATALOG_REPLACE".equals(before.getOperationType())) {
                        if (runs == null) throw BizException.of(ErrorCode.CONFLICT);
                        int scheduled =
                                runs.update(
                                        Wrappers.<CimSyncRun>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("store_no", store)
                                                .eq("operation_no", number)
                                                .in(
                                                        "status",
                                                        "QUEUED",
                                                        "UNKNOWN",
                                                        "SUBMITTED",
                                                        "RUNNING")
                                                .set(
                                                        "next_recovery_at",
                                                        LocalDateTime.now(ZoneOffset.UTC)));
                        if (scheduled != 1) throw BizException.of(ErrorCode.CONFLICT);
                    }
                    if ("MAPPING_VERIFY".equals(before.getOperationType())) {
                        if (mappings == null) throw BizException.of(ErrorCode.CONFLICT);
                        int scheduled =
                                mappings.update(
                                        Wrappers.<CimResourceMapping>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("store_no", store)
                                                .eq("mapping_no", before.getBusinessRef())
                                                .set(
                                                        "next_verification_at",
                                                        LocalDateTime.now(ZoneOffset.UTC)));
                        if (scheduled != 1) throw BizException.of(ErrorCode.CONFLICT);
                    }
                    var op = scoped(entity, store, number, true);
                    if (!request.version().equals(op.getVersion())
                            || !Set.of(
                                            "QUEUED",
                                            "UNKNOWN",
                                            "SUBMITTED",
                                            "SENDING",
                                            "EXCHANGING",
                                            "RUNNING")
                                    .contains(op.getStatus())
                            || refreshOnly && "QUEUED".equals(op.getStatus()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    // The recovery reason stays in this journal; the original supplier reason is
                    // part of its frozen request.
                    operations.update(
                            Wrappers.<CimOperation>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", op.getId())
                                    .eq("version", request.version())
                                    .set("next_recovery_at", LocalDateTime.now(ZoneOffset.UTC))
                                    .set("updated_by", actor)
                                    .set("version", request.version() + 1));
                    return getOperation(entity, number);
                },
                OperationVO::operationNo);
    }

    private CimOperation scoped(String entity, String store, String number, boolean lock) {
        CommerceValidation.text(entity, 32);
        if (store != null) CommerceValidation.text(store, 32);
        CommerceValidation.text(number, 32);
        var q =
                Wrappers.<CimOperation>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("operation_no", number);
        if (store == null) q.isNull("store_no");
        else q.eq("store_no", store);
        if (lock) q.last("FOR UPDATE");
        var row = operations.selectOne(q);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    static OperationVO view(CimOperation op, CimOperationAttempt attempt) {
        Instant expires =
                op.getExpiresAt() == null ? null : op.getExpiresAt().toInstant(ZoneOffset.UTC);
        String url =
                "SUBMITTED".equals(op.getStatus())
                                && op.getCallbackConsumedAt() == null
                                && expires != null
                                && expires.isAfter(Instant.now())
                        ? op.getAuthorizationUrl()
                        : null;
        return new OperationVO(
                op.getOperationNo(),
                op.getOperationType(),
                op.getStatus(),
                op.getAccountNo(),
                url,
                expires,
                attempt == null || attempt.getErrorCode() == null
                        ? op.getReasonCode()
                        : attempt.getErrorCode(),
                op.getVersion());
    }
}
