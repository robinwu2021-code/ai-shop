package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.promotion.entity.PmtActivityScope;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtCouponVersion;
import ai.neargo.shop.promotion.mapper.PmtActivityScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponVersionMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Publishes effective merchant coupon rules inside the caller's locked Core transaction. */
@Component
public class PmtCouponVersionPublisher {
    private static final List<String> DEFINITION_FIELDS = List.of(
            "title", "funder", "benefitMode", "benefitValue", "benefitCapMinor", "benefitRef",
            "minAmountMinor", "minQty", "scopeType", "scopeDesc", "validityMode", "startAt",
            "endAt", "validDays", "issueMode", "redeemMode", "timesTotal", "totalCount",
            "perUserLimit", "budgetMinor");

    private final CouponMapper coupons;
    private final CouponScopeMapper currentScopes;
    private final UserCouponMapper held;
    private final CouponVersionMapper versions;
    private final PmtActivityScopeMapper scopes;
    private final ObjectMapper json;

    public PmtCouponVersionPublisher(CouponMapper coupons, CouponScopeMapper currentScopes,
                                     UserCouponMapper held, CouponVersionMapper versions,
                                     PmtActivityScopeMapper scopes, ObjectMapper json) {
        this.coupons = coupons;
        this.currentScopes = currentScopes;
        this.held = held;
        this.versions = versions;
        this.scopes = scopes;
        this.json = json;
    }

    String publishLocked(PmtCoupon c) {
        String prior = c.getTemplateVersionNo();
        boolean eligible = eligible(c);
        if (prior == null && (!eligible || c.getReceivedCount() != 0
                || held.firstIssuedId(c.getCouponNo()) != null)) return null;

        String next = null;
        if (eligible) {
            List<String> refs = scopeReferences(c);
            if (!refs.isEmpty()) next = append(c, refs);
        }
        if (next != null || prior != null) {
            coupons.update(null, Wrappers.<PmtCoupon>lambdaUpdate()
                    .eq(PmtCoupon::getId, c.getId()).set(PmtCoupon::getTemplateVersionNo, next));
            c.setTemplateVersionNo(next);
        }
        return next;
    }

    private static boolean eligible(PmtCoupon c) {
        return c.getEntityNo() != null && PmtCoupon.BY_MERCHANT.equals(c.getFunder())
                && c.timesTotalOrOne() == 1 && PmtCoupon.REDEEM_ORDER.equals(c.getRedeemMode())
                && (PmtCoupon.ISSUE_CENTER.equals(c.getIssueMode()) || PmtCoupon.ISSUE_TARGETED.equals(c.getIssueMode()))
                && (PmtCoupon.SCOPE_ALL.equals(c.getScopeType()) || PmtCoupon.SCOPE_STORE.equals(c.getScopeType()))
                && (PmtCoupon.CASH.equals(c.getBenefitMode()) && c.getBenefitValue() != null && c.getBenefitValue() > 0
                    || PmtCoupon.PERCENT.equals(c.getBenefitMode()) && c.getBenefitValue() != null
                    && c.getBenefitValue() >= 1000 && c.getBenefitValue() < 10000
                    && c.getBenefitCapMinor() != null && c.getBenefitCapMinor() > 0)
                && (PmtCoupon.RELATIVE.equals(c.getValidityMode()) && c.getValidDays() != null && c.getValidDays() > 0
                    || PmtCoupon.ABSOLUTE.equals(c.getValidityMode()) && c.getStartAt() != null
                    && c.getEndAt() != null && c.getEndAt() > c.getStartAt());
    }

    private List<String> scopeReferences(PmtCoupon c) {
        var rows = currentScopes.selectList(Wrappers.<PmtCouponScope>lambdaQuery()
                .eq(PmtCouponScope::getCouponNo, c.getCouponNo())
                .orderByAsc(PmtCouponScope::getId).last("FOR UPDATE"));
        if (PmtCoupon.SCOPE_ALL.equals(c.getScopeType())) return rows.isEmpty() ? List.of("*") : List.of();
        if (rows.stream().anyMatch(row -> !c.getScopeType().equals(row.getScopeType())
                || !c.getTenantNo().equals(row.getTenantNo()) || row.getRefNo().isBlank())) return List.of();
        return rows.stream().map(PmtCouponScope::getRefNo).toList();
    }

    public static String ruleSnapshot(ObjectMapper json, PmtCoupon c) {
        Map<String, Object> definition = json.convertValue(c, new TypeReference<>() {});
        definition.keySet().retainAll(DEFINITION_FIELDS);
        return json.writeValueAsString(definition);
    }

    private String append(PmtCoupon c, List<String> refs) {
        String versionNo = BizKey.next(BizKey.PROMO_COUPON_VERSION);
        var row = new PmtCouponVersion();
        row.setTenantNo(c.getTenantNo());
        row.setEntityNo(c.getEntityNo());
        row.setCouponNo(c.getCouponNo());
        row.setVersionNo(versionNo);
        row.setRuleSnapshot(ruleSnapshot(json, c));
        versions.insert(row);
        for (int i = 0; i < refs.size(); i++) {
            var scope = new PmtActivityScope();
            scope.setTenantNo(c.getTenantNo());
            scope.setOwnerType("ENT_TEMPLATE");
            scope.setOwnerNo(c.getCouponNo());
            scope.setVersionNo(versionNo);
            scope.setScopeDimension("TARGET");
            scope.setScopeKind(c.getScopeType());
            scope.setRefNo(refs.get(i));
            scope.setIncluded(1);
            scope.setSortNo(i);
            scopes.insert(scope);
        }
        return versionNo;
    }
}
