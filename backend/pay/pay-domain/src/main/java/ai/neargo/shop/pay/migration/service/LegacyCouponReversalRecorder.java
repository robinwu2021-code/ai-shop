package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseReversal;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntUseReversalMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Record an already applied whole-coupon return under the original usage writer's lock. */
@Component
public class LegacyCouponReversalRecorder {
    private final EntUseReversalMapper reversals;

    public LegacyCouponReversalRecorder(EntUseReversalMapper reversals) {
        this.reversals = reversals;
    }

    String originalUsageNo(EntitlementContext ctx, String decisionNo) {
        if (decisionNo == null) return null;
        var prior = reversals.find(ctx.tenantNo(), reverseNo("LEGACY", decisionNo));
        return prior == null ? null : prior.getUsageNo();
    }

    static String partDecision(EntUsage usage, String afterSaleNo) {
        return "CP" + EntitlementRecords.digest(usage.getOperationNo() + ":" + afterSaleNo).substring(0, 62);
    }

    private static String reverseNo(String protocol, String decisionNo) {
        return "CR" + EntitlementRecords.digest(protocol + ":" + decisionNo).substring(0, 62);
    }

    @Transactional(transactionManager="memberMarketingTxManager", propagation=Propagation.MANDATORY)
    public boolean record(EntitlementContext ctx, EntUsage usage, String decisionNo, String reason,
                          String sourceNo, String snapshot, LocalDateTime occurredAt) {
        return record(ctx, usage, decisionNo, reason, sourceNo, snapshot, occurredAt, usage.getAmountMinor(), 1);
    }

    boolean record(EntitlementContext ctx, EntUsage usage, String decisionNo, String reason,
                   String sourceNo, String snapshot, LocalDateTime occurredAt, long discountMinor, int units) {
        String type = switch (reason) {
            case "CANCELLED" -> "ORDER_CANCEL";
            case "FULL_REFUND", "PARTIAL_REFUND" -> "AFTER_SALE";
            default -> throw BizException.of(ErrorCode.CONFLICT);
        };
        String reverseNo = reverseNo(usage.getProtocol(), decisionNo);
        String hash = EntitlementRecords.digest(snapshot);
        var prior = reversals.lock(ctx.tenantNo(), reverseNo);
        if (prior != null) {
            if (!usage.getUsageNo().equals(prior.getUsageNo()) || !hash.equals(prior.getPayloadHash()))
                throw BizException.of(ErrorCode.CONFLICT);
            return false;
        }
        var row = new EntUseReversal();
        if (units == 1 && "FULL_REFUND".equals(reason)) {
            // A final closure can refer to an already attributed refund (e.g. delayed financial closure).
            var partial = reversals.lock(ctx.tenantNo(), reverseNo(usage.getProtocol(), partDecision(usage, sourceNo)));
            if (partial != null) {
                if (!usage.getUsageNo().equals(partial.getUsageNo()) || partial.getUnits() != 0
                        || partial.getDiscountMinor() > discountMinor) throw BizException.of(ErrorCode.CONFLICT);
                row.setSourceStage("CLOSURE");
                discountMinor -= partial.getDiscountMinor();
            }
        }
        EntitlementRecords.audit(row, ctx);
        row.setReverseNo(reverseNo);
        row.setUsageNo(usage.getUsageNo());
        row.setOriginOperationNo(usage.getOperationNo());
        row.setInstanceNo(usage.getLegacyInstanceNo());
        row.setSourceType(type);
        row.setSourceNo(sourceNo);
        row.setAfterSaleNo("AFTER_SALE".equals(type) ? sourceNo : null);
        row.setSourceDecisionNo(decisionNo);
        row.setUnits(units);
        row.setDiscountMinor(discountMinor);
        row.setPayloadHash(hash);
        row.setCommandSnapshot(snapshot);
        row.setOccurredAt(occurredAt);
        EntitlementRecords.changed(reversals.insert(row));
        return true;
    }
}
