package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current authenticated identity and trusted store scope are the only authority passed to owners.
 */
@RestController("beautyBizBusinessTraceController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/traces")
public class BizBusinessTraceController {
    private final ObjectProvider<WorkOrderBillService> workOrderBillService;
    private final ObjectProvider<StoreDirectoryPort> directories;

    /** Lazy API dependencies keep disabled and ops assembly free of business writers. */
    public BizBusinessTraceController(
            ObjectProvider<WorkOrderBillService> workOrderBillService,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.workOrderBillService = workOrderBillService;
        this.directories = directories;
    }

    /** Delegates GET_TRACE without creating alternative business state or authority. */
    @GetMapping("/{businessNo}")
    public BusinessTraceVO getTrace(
            @PathVariable("businessNo") String businessNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false)
                    String requestedStore) {
        BookingStoreScope.requireResourceNo(businessNo);
        BookingStoreScope.require(directories, requestedStore);
        return workOrderBillService
                .getObject()
                .getTrace(
                        BizContext.requireMerchantNo(),
                        BizContext.requireStoreNo(),
                        SecurityUtils.currentUserNo(),
                        businessNo);
    }
}
