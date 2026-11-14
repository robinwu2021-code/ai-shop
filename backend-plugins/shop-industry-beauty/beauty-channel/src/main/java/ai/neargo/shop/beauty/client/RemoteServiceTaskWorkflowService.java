package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.HandoverTaskReq;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi;
import ai.neargo.shop.svc.InternalBindings;

/** Same owner workflow over fixed bound HTTP; ambiguity never creates a replacement command. */
public final class RemoteServiceTaskWorkflowService implements ServiceTaskWorkflowService {
    private final BeautyTaskWorkflowInternalApi api;
    private final InternalBindings bindings;

    public RemoteServiceTaskWorkflowService(
            BeautyTaskWorkflowInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public TaskActionResultVO accept(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            AcceptTaskReq request,
            String key) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "ACCEPT",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Accept(workOrder, task, request, key),
                bindings,
                api::accept);
    }

    @Override
    public TaskWorkflowVO start(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            StartServiceTaskReq request,
            String key) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "START",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Start(workOrder, task, request, key),
                bindings,
                api::start);
    }

    @Override
    public String proposeHandover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            HandoverTaskReq request,
            String key) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "PROPOSE_HANDOVER",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Handover(workOrder, task, request, key),
                bindings,
                api::proposeHandover);
    }

    @Override
    public TaskWorkflowVO act(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            TaskWorkflowReq request,
            String key) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "ACT",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Act(workOrder, task, request, key),
                bindings,
                api::act);
    }

    @Override
    public TaskWorkflowVO query(
            String entity, String store, String actor, String workOrder, String task) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "QUERY",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Query(workOrder, task),
                bindings,
                api::query);
    }

    @Override
    public TaskWorkflowVO recover(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "RECOVER",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Recover(workOrder, task, operationNo),
                bindings,
                api::recover);
    }

    @Override
    public TaskWorkflowVO cancelPending(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            String operationNo) {
        return BeautyReceptionCalls.call(
                BeautyTaskWorkflowInternalApi.ENDPOINT,
                "CANCEL_PENDING",
                entity,
                store,
                actor,
                task,
                new BeautyTaskWorkflowInternalApi.Cancel(workOrder, task, operationNo),
                bindings,
                api::cancelPending);
    }
}
