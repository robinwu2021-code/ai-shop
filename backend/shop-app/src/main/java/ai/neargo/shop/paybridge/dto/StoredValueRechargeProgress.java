package ai.neargo.shop.paybridge.dto;

import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import java.time.Instant;

/** Business progress distinguishes a durable core intent from an acknowledged pay collection order. */
public record StoredValueRechargeProgress(String rechargeNo, String status, String userNo, String entityNo,
                                          String storeNo, String currency, Long paidAmountMinor,
                                          Long creditedTotalMinor, Instant receivedAt, Instant creditedAt,
                                          long revision,String documentNo) {
    public StoredValueRechargeProgress(String rechargeNo,String status,String userNo,String entityNo,String storeNo,
            String currency,Long paidAmountMinor,Long creditedTotalMinor,Instant receivedAt,Instant creditedAt,long revision) {
        this(rechargeNo,status,userNo,entityNo,storeNo,currency,paidAmountMinor,creditedTotalMinor,receivedAt,creditedAt,revision,null);
    }
    public static StoredValueRechargeProgress from(RechargeView r) {
        return from(r,null);
    }
    /** Receipt writers keep the original internal ID; carry the already-authorized original human number. */
    public static StoredValueRechargeProgress from(RechargeView r,String originalDocumentNo) {
        return new StoredValueRechargeProgress(r.rechargeNo(), r.status().name(), r.userNo(), r.entityNo(),
                r.storeNo(), r.currency(), r.paidAmountMinor(), r.creditedTotalMinor(), r.receivedAt(), r.creditedAt(),
                r.revision(),r.documentNo()!=null?r.documentNo():originalDocumentNo);
    }
    public static StoredValueRechargeProgress pending(RechargeIntentPlan p) {
        return new StoredValueRechargeProgress(p.rechargeNo(), "PROCESSING", p.permit().userNo(),
                p.permit().entityNo(), p.permit().storeNo(), p.permit().currency(), p.permit().payAmountMinor(),
                Math.addExact(p.permit().payAmountMinor(), p.permit().bonusAmountMinor()), null, null, 0);
    }
}
