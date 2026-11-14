package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
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

@RestController
@Profile("api")
@ConditionalOnBeautyWebEnabled
@RequestMapping("/biz/x/beauty/work-orders/{workOrderNo}")
public class BizWorkOrderSettlementController {
    private final ObjectProvider<WorkOrderSettlementService> service;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizWorkOrderSettlementController(ObjectProvider<WorkOrderSettlementService> service,
                                            ObjectProvider<StoreDirectoryPort> directories) {
        this.service = service;
        this.directories = directories;
    }
    @GetMapping("/settlement")
    public WorkOrderSettlementVO get(@PathVariable String workOrderNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        scope(workOrderNo, requestedStore);
        return service.getObject().get(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), workOrderNo);
    }
    @PostMapping("/settlement-previews")
    public WorkOrderSettlementPreviewVO preview(@PathVariable String workOrderNo,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore,
            @Valid @RequestBody PreviewWorkOrderSettlementReq request) {
        scope(workOrderNo, requestedStore);
        return service.getObject().preview(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), workOrderNo, request);
    }
    private void scope(String no, String store) {
        BookingStoreScope.requireResourceNo(no);
        BookingStoreScope.require(directories, store);
    }
}
