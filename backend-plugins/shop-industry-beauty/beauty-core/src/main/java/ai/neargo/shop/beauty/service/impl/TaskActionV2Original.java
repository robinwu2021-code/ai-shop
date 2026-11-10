package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.Action;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict V2-only codec. V1/TASK_ACCEPT parsers and all historical digest domains remain unchanged.
 */
final class TaskActionV2Original {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private TaskActionV2Original() {}

    static TaskActionIntentV2 signed(TaskActionIntentV2 i) {
        return new TaskActionIntentV2(
                i.schema(),
                i.actionNo(),
                i.purpose(),
                i.context(),
                i.requestKeyHash(),
                i.requestHash(),
                i.originalBinding(),
                i.reference(),
                i.ownSlots(),
                i.acceptedRoster(),
                i.initialPlan(),
                i.expected(),
                i.observedAtPreparation(),
                i.actor(),
                i.currentPersonnel(),
                i.canonicalAccountNo(),
                i.namespaceHash(),
                i.targetHash(),
                ExecutionEvidenceContract.digest("BEAUTY_TASK_ACTION_INTENT_V2", i, "intentHash"));
    }

    static TaskActionIntentV2 read(BtyServiceSubmission row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var i = JSON.readValue(row.getFrozenCommand(), TaskActionIntentV2.class);
        require(
                "TASK_ACTION".equals(row.getAction())
                        && "TASK_INTENDED".equals(row.getStatus())
                        && "TASK_ACTION_V2".equals(i.schema())
                        && i.purpose() == Action.START_TASK
                        && i.actionNo().equals(row.getSubmissionNo())
                        && i.intentHash().equals(row.getPayloadHash())
                        && i.requestKeyHash().equals(row.getRequestKeyHash())
                        && i.context().scope().tenantNo().equals(row.getTenantNo())
                        && i.context().scope().entityNo().equals(row.getEntityNo())
                        && i.context().scope().storeNo().equals(row.getStoreNo())
                        && i.context().workOrderNo().equals(row.getWorkOrderNo())
                        && i.equals(signed(i))
                        && row.getDeleted() == 0
                        && row.getVersion() == 0
                        && row.getFailureCode() == null
                        && row.getNextRetryAt() == null
                        && row.getExecutionNo() == null
                        && row.getActionObservationJson() == null
                        && !i.ownSlots().isEmpty()
                        && !i.acceptedRoster().isEmpty()
                        && JSON.readTree(row.getFrozenCommand())
                                .equals(JSON.readTree(ServiceExecutionContract.canonical(i))));
        i.reference().validate();
        require(
                i.reference()
                        .bindingHash()
                        .equals(ServiceExecutionContract.bindingHash(i.originalBinding())));
        return i;
    }

    static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
