package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Authoritative core facts for the original ORDER-coupon checkout contract, not a new stored-value/card authorization. */
public interface LegacyCouponCheckoutSourcePort {
    CheckoutFact resolve(EntitlementContext context,String checkoutNo);
    Optional<Decision> decision(EntitlementContext context,String checkoutNo);
    Optional<Reversal> reversal(EntitlementContext context,String checkoutNo);

    /** Group identity is the original pricing key; absent storeNo is not replaced by a fabricated store. */
    record MerchantGroup(String groupKey,String merchantNo,String storeNo,long goodsAmountMinor) {
        public MerchantGroup {
            ContractChecks.id(groupKey); ContractChecks.id(merchantNo); ContractChecks.optionalId(storeNo);
            ContractChecks.require(goodsAmountMinor>=0);
        }
    }
    enum IntentKind { QUOTE_ONLY, CHECKOUT }
    /** Immutable economic facts remain unchanged when the separate source decision becomes terminal. */
    record CheckoutFact(int protocolVersion,IntentKind intentKind,String tenantNo,String checkoutNo,String operationNo,String userNo,String entityNo,
                        SourceIdentity origin,String targetInstanceNo,String ownerBatchNo,String ownerPlanHash,
                        String currency,List<MerchantGroup> groups,String economicHash,String selectionActorNo,
                        Instant createdAt,Instant deadlineAt) {
        public CheckoutFact {
            ContractChecks.require(protocolVersion==1); ContractChecks.present(intentKind);
            ContractChecks.id(tenantNo); ContractChecks.id(checkoutNo); ContractChecks.id(operationNo);
            ContractChecks.id(userNo); ContractChecks.id(entityNo); ContractChecks.present(origin);
            ContractChecks.require(origin.sourceKind()==SourceKind.INSTANCE);
            ContractChecks.id(targetInstanceNo); ContractChecks.id(ownerBatchNo); ContractChecks.hash(ownerPlanHash);
            ContractChecks.currency(currency); ContractChecks.hash(economicHash); ContractChecks.id(selectionActorNo);
            groups=List.copyOf(ContractChecks.present(groups));
            ContractChecks.require(!groups.isEmpty() && groups.stream().map(MerchantGroup::groupKey).distinct().count()==groups.size());
            long amount=0;
            for(var group:groups) amount=ContractChecks.addAmounts(amount,group.goodsAmountMinor());
            ContractChecks.present(createdAt); ContractChecks.present(deadlineAt);
            ContractChecks.require(createdAt.isBefore(deadlineAt));
        }
    }
    enum Outcome { COMMIT, ABORT }
    record Decision(String checkoutNo,String operationNo,String decisionNo,Outcome outcome,String orderNo,
                    String economicHash,Long discountMinor,Instant occurredAt) {
        public Decision {
            ContractChecks.id(checkoutNo); ContractChecks.id(operationNo); ContractChecks.id(decisionNo);
            ContractChecks.present(outcome); ContractChecks.hash(economicHash); ContractChecks.present(occurredAt);
            if(outcome==Outcome.COMMIT) {
                ContractChecks.id(orderNo); ContractChecks.require(discountMinor!=null && discountMinor>0);
            } else ContractChecks.require(orderNo==null && discountMinor==null);
        }
    }
    enum ReverseReason { CANCELLED, FULL_REFUND, PARTIAL_REFUND }
    record RefundPart(String afterSaleNo, long amountMinor) {
        public RefundPart {
            ContractChecks.id(afterSaleNo);
            ContractChecks.require(amountMinor > 0);
        }
    }
    record Reversal(String checkoutNo,String operationNo,String decisionNo,String orderNo,String sourceRef,
                    String economicHash,String currency,long discountMinor,ReverseReason reason,Instant occurredAt,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                    List<RefundPart> parts) {
        public Reversal(String checkoutNo,String operationNo,String decisionNo,String orderNo,String sourceRef,
                        String economicHash,String currency,long discountMinor,ReverseReason reason,Instant occurredAt) {
            this(checkoutNo,operationNo,decisionNo,orderNo,sourceRef,economicHash,currency,discountMinor,reason,occurredAt,List.of());
        }
        public Reversal {
            ContractChecks.id(checkoutNo); ContractChecks.id(operationNo); ContractChecks.id(decisionNo);
            ContractChecks.id(orderNo); ContractChecks.id(sourceRef); ContractChecks.hash(economicHash);
            ContractChecks.currency(currency); ContractChecks.require(discountMinor>0);
            ContractChecks.present(reason); ContractChecks.present(occurredAt);
            parts = parts == null ? List.of() : List.copyOf(parts);
            long sum = parts.stream().mapToLong(RefundPart::amountMinor).reduce(0, Math::addExact);
            ContractChecks.require(parts.stream().map(RefundPart::afterSaleNo).distinct().count() == parts.size());
            ContractChecks.require(reason == ReverseReason.PARTIAL_REFUND ? sum == discountMinor : sum <= discountMinor);
            if (reason != ReverseReason.PARTIAL_REFUND)
                ContractChecks.require(parts.stream().noneMatch(part -> sourceRef.equals(part.afterSaleNo())));
        }
    }
}
