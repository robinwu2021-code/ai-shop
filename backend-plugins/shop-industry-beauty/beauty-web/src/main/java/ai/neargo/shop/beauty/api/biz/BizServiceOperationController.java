package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public ingress derives scope and identity exclusively from the current authenticated context. */
@RestController("beautyBizServiceOperationController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/operations")
public class BizServiceOperationController {
    private final ObjectProvider<ServiceOperationQueryService> serviceOperationQueryService;
    private final ObjectProvider<ServiceOperationRecoveryService> serviceOperationRecoveryService;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizServiceOperationController(ObjectProvider<ServiceOperationQueryService>
            serviceOperationQueryService, ObjectProvider<ServiceOperationRecoveryService>
            serviceOperationRecoveryService, ObjectProvider<StoreDirectoryPort> directories) {
        this.serviceOperationQueryService = serviceOperationQueryService;
        this.serviceOperationRecoveryService = serviceOperationRecoveryService;
        this.directories = directories;
    }

    /** getOperation retains the approved public contract and delegates business authorization. */
    @GetMapping("/{operationNo}")
    public OperationVO getOperation(@PathVariable("operationNo") String operationNo, @RequestHeader(value =
            BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return serviceOperationQueryService.getObject().getOperation(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), operationNo);
    }

    /** recoverOperation retains the approved public contract and delegates business authorization. */
    @PostMapping("/{operationNo}/recoveries")
    public OperationVO recoverOperation(@PathVariable("operationNo") String operationNo, @RequestHeader(value =
            BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return serviceOperationRecoveryService.getObject().recoverOperation(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), operationNo);
    }

    private void requireStore(String requestedStore) {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo()) || !"MERCHANT".equals(user.realm().name())
                || !("MCH".equals(user.subjectKind().name()) || "USR".equals(user.subjectKind().name()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var context = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var target = directories.getObject().cards(List.of(store)).get(store);
        if (target == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, target.entityNo(), target.storeNo(),
                requestedStore, context.storeNos());
    }
}
