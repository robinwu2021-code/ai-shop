package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.promotion.service.BrandCouponService;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Scope structure is insufficient: each reference must resolve to a current fact in the owning domain. */
@Service
public class EntitlementScopeValidationServiceImpl implements EntitlementScopeValidationService {
    private final ProductScopeFactsPort product;
    private final MerchantScopeFactsPort merchant;
    private final BrandCouponSourcePort brandSources;
    private final BrandCouponService brandCoupons;

    public EntitlementScopeValidationServiceImpl(ProductScopeFactsPort product, MerchantScopeFactsPort merchant,
                                                 BrandCouponSourcePort brandSources, BrandCouponService brandCoupons) {
        this.product = product;
        this.merchant = merchant;
        this.brandSources = brandSources;
        this.brandCoupons = brandCoupons;
    }

    @Override
    public void validateDefinition(String tenantNo, String entityNo, EntitlementDefinition definition) {
        if (definition == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (definition.redemptionScope() != null && "BRAND_STORES".equals(definition.redemptionScope().mode())) {
            brandSources.validateTemplate(tenantNo, entityNo, definition);
            return;
        }
        validate(tenantNo, entityNo, definition.redemptionScope());
        if ("GIFT".equals(definition.benefitMode())) {
            try {
                ContractChecks.id(definition.benefitRef());
            } catch (IllegalArgumentException invalidRef) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (!product.goodsOwned(tenantNo, entityNo, Set.of(definition.benefitRef()))) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
    }

    @Override
    public void validate(String tenantNo, String entityNo, RedemptionScope scope) {
        try {
            ContractChecks.id(tenantNo);
            ContractChecks.require(tenantNo.length() <= 32);
            ContractChecks.id(entityNo);
            ContractChecks.present(scope);
            ContractChecks.require(scope.mode() != null
                    && Set.of("ALL", "STORES", "GOODS", "CATEGORIES", "BRAND_STORES").contains(scope.mode()));
            ContractChecks.present(scope.refs());
            scope.refs().forEach(ContractChecks::id);
            ContractChecks.require(scope.refs().stream().distinct().count() == scope.refs().size());
            ContractChecks.require("ALL".equals(scope.mode()) ? scope.refs().isEmpty() : !scope.refs().isEmpty());
            ContractChecks.require(!"BRAND_STORES".equals(scope.mode()) || scope.refs().size() == 1);
        } catch (IllegalArgumentException invalidScope) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!merchant.entityActive(tenantNo, entityNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if ("BRAND_STORES".equals(scope.mode())) {
            var source = brandCoupons.sourceFact(tenantNo, entityNo, scope.refs().getFirst());
            brandSources.validateTemplate(tenantNo, entityNo, source.snapshot().definition());
            return;
        }
        Set<String> refs = Set.copyOf(scope.refs());
        boolean valid = switch (scope.mode()) {
            case "ALL" -> true;
            case "STORES" -> merchant.storesOwned(tenantNo, entityNo, refs);
            case "GOODS" -> product.goodsOwned(tenantNo, entityNo, refs);
            case "CATEGORIES" -> product.categoriesActive(tenantNo, refs);
            default -> false;
        };
        if (!valid) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
}
