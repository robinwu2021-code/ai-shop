package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.common.PageData;
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
@RequestMapping("/biz/x/beauty/service-definitions")
public class BizServiceDefinitionController {
    private final ObjectProvider<ServiceDefinitionService> services;
    private final ObjectProvider<StoreDirectoryPort> directories;
    public BizServiceDefinitionController(ObjectProvider<ServiceDefinitionService> services,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.services = services;
        this.directories = directories;
    }

    /** Reading settings reuses the platform store-settings permission and grants no trading authority. */
    @GetMapping
    @PreAuthorize("@perm.canBiz('biz:store')")
    public PageData<ServiceDefinitionVO> listDefinitions(@Valid @ModelAttribute ServiceDefinitionQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return services.getObject().listDefinitions(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), query);
    }

    /** Durable keys and current head revisions protect publication from retries and concurrent edits. */
    @PostMapping("/versions")
    @PreAuthorize("@perm.canBiz('biz:store')")
    public ServiceDefinitionVersionVO publishDefinitionVersion(
            @Valid @RequestBody PublishServiceDefinitionVersionReq req,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return services.getObject().publishDefinitionVersion(BizContext.requireMerchantNo(),
                BizContext.requireStoreNo(),
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
