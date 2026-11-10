package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.HandoverTaskReq;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;

/**
 * Consumed service workflow; owner originals remain internal and cannot be forged by public
 * clients.
 */
public interface ServiceTaskWorkflowService {
    TaskActionResultVO accept(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            AcceptTaskReq request,
            String key);

    TaskWorkflowVO start(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            StartServiceTaskReq request,
            String key);

    String proposeHandover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            HandoverTaskReq request,
            String key);

    TaskWorkflowVO act(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            TaskWorkflowReq request,
            String key);

    TaskWorkflowVO query(String entity, String store, String actor, String workOrder, String task);

    TaskWorkflowVO recover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo);

    TaskWorkflowVO cancelPending(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo);
}
