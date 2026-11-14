package ai.neargo.shop.portal.biz;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.PlanCatalog;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.commercebridge.ShopCommerceActorAdapter;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix.Matrix;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix.PayActivation;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix.StoreFeatures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店功能矩阵（TDD-B端门店功能矩阵与方案准入）。只读，只给端上裁剪入口与引导用；
 * 真正的拦截仍是各接口自己的权限码 + 业务端准入。
 */
@RestController
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class BizStoreFeatureController {
    private static final Logger log = LoggerFactory.getLogger(BizStoreFeatureController.class);

    private final FoundationQueryFacade queries;
    private final BizPaymentAppService payments;

    public BizStoreFeatureController(FoundationQueryFacade queries, BizPaymentAppService payments) {
        this.queries = queries;
        this.payments = payments;
    }

    /** 某个经营类型三档方案分别包含哪些功能、预置角色各能用到哪一级（开店选方案、方案比较页） */
    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/business-types/{typeCode}/feature-matrix")
    public Matrix matrix(@PathVariable String typeCode) {
        return StoreFeatureMatrix.forBusinessType(queries.plans(ShopCommerceActorAdapter.current(), typeCode));
    }

    /** 当前用户在当前门店每个功能的状态与下一步（工作台入口、锁定态） */
    @PreAuthorize("@perm.memberOfSelectedStore(#storeNo)")
    @GetMapping("/biz/stores/{storeNo}/features")
    public StoreFeatures features(@PathVariable String storeNo) {
        var actor = ShopCommerceActorAdapter.current();
        var store = queries.store(actor, new StoreRef(actor.tenantNo(), storeNo));
        PlanCatalog catalog = store.businessTypeCode() == null ? null
                : queries.plans(actor, store.businessTypeCode());
        // 经营类型没有已发布方案（旧店常见）：不按行业过滤，免得旧店一个入口都没有
        if (catalog != null && (catalog.plans() == null || catalog.plans().isEmpty())) catalog = null;
        return StoreFeatureMatrix.forStore(store, catalog, BizContext.current().effectivePerms(),
                payActivation(store.storeNo()));
    }

    /** 收款服务可能是远程 pay-svc：读不到时给 UNKNOWN，端上显示「重试」，不冒充「未开通」 */
    private PayActivation payActivation(String storeNo) {
        try {
            return payments.collectStatus(storeNo).enabled() ? PayActivation.ACTIVE : PayActivation.NOT_ACTIVE;
        } catch (RuntimeException e) {
            log.warn("collect status unavailable for store {}: {}", storeNo, e.toString());
            return PayActivation.UNKNOWN;
        }
    }
}
