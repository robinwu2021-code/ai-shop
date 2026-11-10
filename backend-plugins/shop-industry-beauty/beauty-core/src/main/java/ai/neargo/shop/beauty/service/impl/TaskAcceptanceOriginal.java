package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.service.impl.TaskAcceptanceCoordinator.TaskAcceptanceRecord;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;

/** Exact historical TASK_ACCEPT decoder shared without depending on acceptance orchestration. */
final class TaskAcceptanceOriginal {
    private TaskAcceptanceOriginal() {}

    static TaskAcceptanceRecord read(AppointmentPersistence db, BtyServiceSubmission row) {
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        require("TASK_ACCEPT".equals(row.getAction()) && "TASK_ACCEPTED".equals(row.getStatus()));
        var record = db.json.readValue(row.getFrozenCommand(), TaskAcceptanceRecord.class);
        require(
                "TASK_ACCEPT_V1".equals(record.schema())
                        && "ACCEPT_TASK".equals(record.purpose())
                        && record.actionNo().equals(row.getSubmissionNo())
                        && record.recordHash().equals(row.getPayloadHash())
                        && record.requestKeyHash().equals(row.getRequestKeyHash())
                        && record.context().scope().tenantNo().equals(row.getTenantNo())
                        && record.context().scope().entityNo().equals(row.getEntityNo())
                        && record.context().scope().storeNo().equals(row.getStoreNo())
                        && record.context().workOrderNo().equals(row.getWorkOrderNo())
                        && row.getVersion() == 0
                        && row.getDeleted() == 0
                        && row.getNextRetryAt() == null
                        && row.getFailureCode() == null
                        && record.equals(signed(record))
                        && ResourceQualificationPort.intact(record.qualification())
                        && record.selected().size() == record.result().size()
                        && !record.result().isEmpty()
                        && record.publishedTaskRevision()
                                == record.requestVersions().taskVersion() + 1
                        && db.json
                                .readTree(row.getFrozenCommand())
                                .equals(
                                        db.json.readTree(
                                                ServiceExecutionContract.canonical(record))));
        record.reference().validate();
        require(
                record.reference()
                        .bindingHash()
                        .equals(ServiceExecutionContract.bindingHash(record.originalBinding())));
        return record;
    }

    static TaskAcceptanceRecord signed(TaskAcceptanceRecord r) {
        return new TaskAcceptanceRecord(
                r.schema(),
                r.purpose(),
                r.actionNo(),
                r.context(),
                r.requestKeyHash(),
                r.requestHash(),
                r.actorNamespace(),
                r.actualActorAudit(),
                r.currentPersonnel(),
                r.originalBinding(),
                r.reference(),
                r.initialPlan(),
                r.requiredRosterHash(),
                r.selected(),
                r.requestVersions(),
                r.acceptanceBinding(),
                r.initialObservationAudit(),
                r.qualification(),
                r.acceptedAt(),
                r.publishedTaskRevision(),
                r.result(),
                ExecutionEvidenceContract.digest("BEAUTY_TASK_ACCEPT_V1", r, "recordHash"));
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
