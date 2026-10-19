package ai.neargo.shop.paybridge.dto;

import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import java.time.Instant;

/** Business progress distinguishes a durable core intent from an acknowledged pay collection order. */
public record StoredValueRechargeProgress(String rechargeNo, String status, String userNo, String entityNo,
                                          String storeNo, String currency, Long paidAmountMinor,
                                          Long creditedTotalMinor, Instant receivedAt, Instant creditedAt,
                                          long revision) {
    public static StoredValueRechargeProgress from(RechargeView r) {
        return new StoredValueRechargeProgress(r.rechargeNo(), r.status().name(), r.userNo(), r.entityNo(),
                r.storeNo(), r.currency(), r.paidAmountMinor(), r.creditedTotalMinor(), r.receivedAt(), r.creditedAt(),
                r.revision());
    }
    public static StoredValueRechargeProgress pending(RechargeIntentPlan p) {
        return new StoredValueRechargeProgress(p.rechargeNo(), "PROCESSING", p.permit().userNo(),
                p.permit().entityNo(), p.permit().storeNo(), p.permit().currency(), p.permit().payAmountMinor(),
                Math.addExact(p.permit().payAmountMinor(), p.permit().bonusAmountMinor()), null, null, 0);
    }
}
