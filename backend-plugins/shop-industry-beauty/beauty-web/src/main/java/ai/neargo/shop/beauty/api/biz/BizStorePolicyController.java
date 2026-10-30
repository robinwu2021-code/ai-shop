package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.beauty.dto.PolicyVersionVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.StorePolicyQuery;
import ai.neargo.shop.beauty.dto.StorePolicyVO;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public ingress derives actor and scope from the platform; query coordinates never grant another store. */
@RestController
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/store-policies")
public class BizStorePolicyController {
    private final ObjectProvider<StorePolicyService> services;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizStorePolicyController(ObjectProvider<StorePolicyService> services,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.services = services;
        this.directories = directories;
    }

    /** Reading settings reuses the platform store-settings permission and grants no trading authority. */
    @GetMapping
    @PreAuthorize("@perm.canBiz('biz:store')")
    public StorePolicyVO getPolicy(@Valid @ModelAttribute StorePolicyQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return services.getObject().getPolicy(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), query);
    }

    /** Durable keys and current head revisions protect publication from retries and concurrent edits. */
    @PostMapping("/versions")
    @PreAuthorize("@perm.canBiz('biz:store')")
    public PolicyVersionVO publishPolicyVersion(@Valid @RequestBody PublishStorePolicyVersionReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return services.getObject().publishPolicyVersion(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), req, idempotencyKey);
    }

    private void requireStore(String requestedStore) {
        BizContext context = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var target = directories.getObject().cards(List.of(store)).get(store);
        if (target == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, target.entityNo(), target.storeNo(),
                requestedStore, context.storeNos());
    }
}
