package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reads complete live local parents without owner IO; source caller authorization remains explicit.
 */
public final class ServiceSubmissionQuery {
    private final AppointmentPersistence db;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.WorkOrderContextMapper contexts;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.ServiceDefinitionMapper definitions;
    private final BeautyMappers.ServiceDefinitionVersionMapper versions;

    public ServiceSubmissionQuery(
            AppointmentPersistence db,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.WorkOrderContextMapper contexts,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ReceptionOperationMapper receptions,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceDefinitionMapper definitions,
            BeautyMappers.ServiceDefinitionVersionMapper versions) {
        this.db = db;
        this.orders = orders;
        this.contexts = contexts;
        this.tasks = tasks;
        this.receptions = receptions;
        this.submissions = submissions;
        this.definitions = definitions;
        this.versions = versions;
    }

    public record Root(
            BtyWorkOrder order,
            BtyWorkOrderContext context,
            WorkOrderCoordinator.WorkOrderScopeSnapshot original,
            BtyServiceSubmission draft,
            List<BtyServiceTask> tasks) {}

    public Root root(String entity, String store, String number) {
        return db.read(
                () -> {
                    var order = orders.selectScoped(entity, store, number);
                    var context = contexts.selectScoped(entity, store, number);
                    if (order == null
                            || context == null
                            || receptions.selectOpenedForWorkOrder(
                                            entity,
                                            store,
                                            number,
                                            context.getSourceReceptionOperationNo())
                                    == null) {
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    }
                    if (order.getAppointmentNo() != null) {
                        var appointment = db.root(order.getAppointmentNo());
                        if (appointment == null
                                || !entity.equals(appointment.getEntityNo())
                                || !store.equals(appointment.getStoreNo())) {
                            throw BizException.of(ErrorCode.NOT_FOUND);
                        }
                    }
                    var original =
                            db.json.readValue(
                                    context.getFrozenScope(),
                                    WorkOrderCoordinator.WorkOrderScopeSnapshot.class);
                    var draft = submissions.selectOpenForWorkOrder(entity, store, number);
                    AppointmentPersistence.require(
                            original.schemaVersion() == 1
                                    && original.originalScopeRevision() == 1
                                    && original.scopeHash() != null
                                    && original.scopeHash().matches("[0-9a-f]{64}")
                                    && draft != null);
                    var opened =
                            db.json.readValue(
                                    draft.getFrozenCommand(),
                                    WorkOrderCoordinator.OpenSubmissionDraft.class);
                    AppointmentPersistence.require(
                            opened.schemaVersion() == 1
                                    && opened.scopeRevision() == 1
                                    && original.scopeHash().equals(opened.scopeHash())
                                    && original.taskRefs().equals(opened.taskRefs())
                                    && opened.identity().kind().equals(context.getIdentityKind())
                                    && opened.identity()
                                            .reference()
                                            .equals(context.getIdentityRef())
                                    && IdempotencyScope.requestHash(
                                                    db.json.writeValueAsString(opened))
                                            .equals(draft.getPayloadHash())
                                    && "RECEIVED".equals(draft.getStatus())
                                    && draft.getNextRetryAt() == null);
                    var rows = tasks.selectForWorkOrder(entity, store, number);
                    for (var reference : original.taskRefs()) {
                        AppointmentPersistence.require(
                                rows.stream()
                                        .anyMatch(
                                                row ->
                                                        reference.taskNo().equals(row.getTaskNo())
                                                                && row.getScopeRevision() == 1
                                                                && reference
                                                                        .skuNo()
                                                                        .equals(row.getSkuNo())
                                                                && reference
                                                                        .definitionVersionNo()
                                                                        .equals(
                                                                                row
                                                                                        .getDefinitionVersionNo())
                                                                && reference
                                                                        .uom()
                                                                        .equals(row.getUom())
                                                                && reference
                                                                                .qty()
                                                                                .compareTo(
                                                                                        row
                                                                                                .getQty())
                                                                        == 0));
                    }
                    for (var row : rows) {
                        var snapshot =
                                db.json.readValue(
                                        row.getExecutionSnapshot(),
                                        WorkOrderCoordinator.TaskExecutionSnapshot.class);
                        AppointmentPersistence.require(
                                snapshot.schemaVersion() == 1
                                        && snapshot.taskSnapshot() != null
                                        && snapshot.taskSnapshot().schemaVersion() == 1
                                        && row.getSkuNo().equals(snapshot.taskSnapshot().skuNo())
                                        && row.getDefinitionVersionNo()
                                                .equals(
                                                        snapshot.taskSnapshot()
                                                                .definitionVersionNo())
                                        && "COUNT".equals(row.getUom())
                                        && row.getQty().compareTo(BigDecimal.ONE) == 0);
                        var version =
                                versions.selectOne(
                                        Wrappers.<BtyServiceDefinitionVersion>lambdaQuery()
                                                .eq(
                                                        BtyServiceDefinitionVersion::getTenantNo,
                                                        "MAIN")
                                                .eq(
                                                        BtyServiceDefinitionVersion::getEntityNo,
                                                        entity)
                                                .eq(
                                                        BtyServiceDefinitionVersion
                                                                ::getDefinitionVersionNo,
                                                        row.getDefinitionVersionNo()));
                        AppointmentPersistence.require(
                                version != null
                                        && version.getSchemaVersion() == 1
                                        && version.getSnapshotHash()
                                                .equals(snapshot.taskSnapshot().definitionHash())
                                        && version.getDurationMinSnapshot()
                                                == snapshot.taskSnapshot().durationMin()
                                        && version.getBufferBeforeMin()
                                                == snapshot.taskSnapshot().bufferBeforeMin()
                                        && version.getBufferAfterMin()
                                                == snapshot.taskSnapshot().bufferAfterMin());
                        var definition =
                                definitions.selectOne(
                                        Wrappers.<BtyServiceDefinition>lambdaQuery()
                                                .eq(BtyServiceDefinition::getTenantNo, "MAIN")
                                                .eq(BtyServiceDefinition::getEntityNo, entity)
                                                .eq(BtyServiceDefinition::getStoreNo, store)
                                                .eq(
                                                        BtyServiceDefinition::getDefinitionNo,
                                                        version.getDefinitionNo())
                                                .eq(
                                                        BtyServiceDefinition::getSkuNo,
                                                        row.getSkuNo()));
                        AppointmentPersistence.require(definition != null);
                    }
                    return new Root(order, context, original, draft, List.copyOf(rows));
                });
    }

    public BtyServiceSubmission submission(String entity, String store, String number) {
        var row = db.read(() -> submissions.selectSubmission(entity, store, number));
        if (row == null || !List.of("SUBMIT_SOURCE", "ADD_SOURCE").contains(row.getAction())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var command =
                db.json.readValue(
                        row.getFrozenCommand(), ServiceSubmissionServiceImpl.Command.class);
        AppointmentPersistence.require(ServiceSubmissionServiceImpl.validCommand(command, row));
        root(entity, store, row.getWorkOrderNo());
        return row;
    }
}
