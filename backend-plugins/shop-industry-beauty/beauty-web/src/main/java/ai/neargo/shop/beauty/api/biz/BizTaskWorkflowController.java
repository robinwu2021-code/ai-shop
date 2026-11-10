package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.AcceptTaskReq;
import ai.neargo.shop.beauty.dto.HandoverTaskReq;
import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskActionResultVO;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actual fulfillment delegates all SELF/coordination policy and effects to the original owner. */
@RestController
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/work-orders/{workOrderNo}/tasks/{taskNo}")
public class BizTaskWorkflowController {
    private final ObjectProvider<ServiceTaskWorkflowService> workflow;
    private final ObjectProvider<StoreDirectoryPort> directories;

    public BizTaskWorkflowController(
            ObjectProvider<ServiceTaskWorkflowService> workflow,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.workflow = workflow;
        this.directories = directories;
    }

    private void scope(String workOrder, String task, String requestedStore) {
        BookingStoreScope.requireResourceNo(workOrder);
        BookingStoreScope.requireResourceNo(task);
        BookingStoreScope.require(directories, requestedStore);
    }

    @PostMapping("/accept")
    public TaskActionResultVO accept(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @Valid @RequestBody AcceptTaskReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireKey(key);
        return workflow.getObject()
                .accept(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        request,
                        key);
    }

    @PostMapping("/start")
    public TaskWorkflowVO start(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @Valid @RequestBody StartServiceTaskReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireKey(key);
        return workflow.getObject()
                .start(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        request,
                        key);
    }

    @PostMapping("/handover-proposals")
    public String proposeHandover(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @Valid @RequestBody HandoverTaskReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireKey(key);
        return workflow.getObject()
                .proposeHandover(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        request,
                        key);
    }

    @PostMapping("/workflow")
    public TaskWorkflowVO act(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @Valid @RequestBody TaskWorkflowReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireKey(key);
        return workflow.getObject()
                .act(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        request,
                        key);
    }

    @GetMapping("/execution")
    public TaskWorkflowVO query(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        return workflow.getObject()
                .query(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task);
    }

    @PostMapping("/operations/{operationNo}/recover")
    public TaskWorkflowVO recover(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @PathVariable("operationNo") String operationNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireResourceNo(operationNo);
        return workflow.getObject()
                .recover(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        operationNo);
    }

    @PostMapping("/operations/{operationNo}/cancel")
    public TaskWorkflowVO cancelPending(
            @PathVariable("workOrderNo") String workOrder,
            @PathVariable("taskNo") String task,
            @PathVariable("operationNo") String operationNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        scope(workOrder, task, requestedStore);
        BookingStoreScope.requireResourceNo(operationNo);
        return workflow.getObject()
                .cancelPending(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrder,
                        task,
                        operationNo);
    }
}
