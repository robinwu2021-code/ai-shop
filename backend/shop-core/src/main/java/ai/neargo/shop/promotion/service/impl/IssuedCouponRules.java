package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtActivityScope;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponVersion;
import ai.neargo.shop.promotion.mapper.PmtActivityScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** An issued coupon keeps its published rules. Status and inventory remain live on the root. */
@Component
class IssuedCouponRules {
    private final CouponVersionMapper versions;
    private final ObjectMapper json;
    private final PmtActivityScopeMapper scopes;

    IssuedCouponRules(CouponVersionMapper versions, ObjectMapper json, PmtActivityScopeMapper scopes) {
        this.versions = versions;
        this.json = json;
        this.scopes = scopes;
    }

    Map<String, PmtCouponVersion> load(String tenant, Collection<String> numbers) {
        if (numbers.isEmpty()) return Map.of();
        return DataScopeContext.executeWithoutScope(() -> versions.selectList(
                Wrappers.<PmtCouponVersion>lambdaQuery().eq(PmtCouponVersion::getTenantNo, tenant)
                        .in(PmtCouponVersion::getVersionNo, numbers))).stream()
                .collect(Collectors.toMap(PmtCouponVersion::getVersionNo, Function.identity()));
    }

    Set<String> stores(PmtCoupon coupon) {
        return DataScopeContext.executeWithoutScope(() -> scopes.selectList(Wrappers.<PmtActivityScope>lambdaQuery()
                .eq(PmtActivityScope::getTenantNo, coupon.getTenantNo())
                .eq(PmtActivityScope::getOwnerType, "ENT_TEMPLATE")
                .eq(PmtActivityScope::getOwnerNo, coupon.getCouponNo())
                .eq(PmtActivityScope::getVersionNo, coupon.getTemplateVersionNo())
                .eq(PmtActivityScope::getScopeDimension, "TARGET")
                .eq(PmtActivityScope::getScopeKind, "STORE")
                .eq(PmtActivityScope::getIncluded, 1))).stream()
                .map(PmtActivityScope::getRefNo).collect(Collectors.toSet());
    }

    PmtCoupon issued(PmtCoupon root, String versionNo) {
        if (root == null || versionNo == null) return root;
        return issued(root, load(root.getTenantNo(), List.of(versionNo)).get(versionNo));
    }

    PmtCoupon issued(PmtCoupon root, PmtCouponVersion version) {
        if (version == null || !root.getCouponNo().equals(version.getCouponNo())
                || !version.getEntityNo().equals(root.getEntityNo())) {
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        }
        PmtCoupon rules = json.readValue(version.getRuleSnapshot(), PmtCoupon.class);
        rules.setTemplateVersionNo(version.getVersionNo());
        rules.setCouponNo(root.getCouponNo());
        rules.setDocumentNo(root.getDocumentNo());
        rules.setTenantNo(root.getTenantNo());
        rules.setEntityNo(root.getEntityNo());
        rules.setStatus(root.getStatus());
        rules.setTotalCount(root.getTotalCount());
        rules.setReceivedCount(root.getReceivedCount());
        return rules;
    }
}
