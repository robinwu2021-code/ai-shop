package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizWorkOrderController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/work-orders")
public class BizWorkOrderController {
    private final ObjectProvider<WorkOrderService> workOrderService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizWorkOrderController(
            ObjectProvider<WorkOrderService> workOrderService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.workOrderService = workOrderService;
        this.directories = directories;
    }

    /** Delegates OPEN_WORK_ORDER without creating alternative business state or authority. */
    @PostMapping
    public WorkOrderVO openWorkOrder(
            @Valid @RequestBody OpenWorkOrderReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        BookingStoreScope.requireBodyStore(req.storeNo());
        return workOrderService
                .getObject()
                .openWorkOrder(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        req,
                        idempotencyKey);
    }

    /** Delegates GET_WORK_ORDER without creating alternative business state or authority. */
    @GetMapping("/{workOrderNo}")
    public WorkOrderDetailVO getWorkOrder(
            @PathVariable("workOrderNo") String workOrderNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(workOrderNo);
        BookingStoreScope.require(directories, requestedStore);
        return workOrderService
                .getObject()
                .getWorkOrder(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrderNo);
    }

    /** Delegates SUBMIT_SERVICES without creating alternative business state or authority. */
    @PostMapping("/{workOrderNo}/service-submissions")
    public ServiceSubmissionVO submitServices(
            @PathVariable("workOrderNo") String workOrderNo,
            @Valid @RequestBody SubmitServicesReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(workOrderNo);
        BookingStoreScope.require(directories, requestedStore);
        BookingStoreScope.requireKey(idempotencyKey);
        return workOrderService
                .getObject()
                .submitServices(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrderNo,
                        req,
                        idempotencyKey);
    }

    /** Delegates GET_BILL without creating alternative business state or authority. */
    @GetMapping("/{workOrderNo}/bill")
    public WorkOrderBillVO getBill(
            @PathVariable("workOrderNo") String workOrderNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(workOrderNo);
        BookingStoreScope.require(directories, requestedStore);
        return workOrderService
                .getObject()
                .getBill(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        workOrderNo);
    }
}
