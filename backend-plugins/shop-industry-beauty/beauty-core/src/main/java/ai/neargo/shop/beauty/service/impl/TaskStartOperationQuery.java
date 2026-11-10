package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/**
 * Safe operation projection needs no Native/Core orchestrator and remains a local authenticated
 * read.
 */
public final class TaskStartOperationQuery {
    private final AppointmentPersistence db;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final TaskStartOriginalReader originals;
    private final ServiceExecutionAccess access;
    private final TaskStartSchemaVerifier schema;

    public TaskStartOperationQuery(
            AppointmentPersistence db,
            BeautyMappers.ServiceSubmissionMapper submissions,
            TaskStartOriginalReader originals,
            ServiceExecutionAccess access,
            TaskStartSchemaVerifier schema) {
        this.db = db;
        this.submissions = submissions;
        this.originals = originals;
        this.access = access;
        this.schema = schema;
    }

    public OperationVO project(String entity, String store, String actor, String number) {
        DelegatedIdentityContext.current(actor);
        schema.requireAvailable();
        var row = db.read(() -> submissions.selectSubmission(entity, store, number));
        var f = TaskStartJournal.readFrozen(row);
        var current = access.requireSelf(f.action().context().scope());
        if (!current.identity().equals(f.action().actor())
                || !current.namespaceHash().equals(f.action().namespaceHash())
                || !current.personnel()
                        .personnelNo()
                        .equals(f.action().currentPersonnel().personnelNo()))
            throw BizException.of(ErrorCode.FORBIDDEN);
        return originals.withStartOriginal(
                f.lookup(),
                o ->
                        new OperationVO(
                                number,
                                null,
                                o.observation().phase().name(),
                                "TASK_START",
                                null,
                                f.action().context().workOrderNo(),
                                o.observation().pendingReason()));
    }
}
