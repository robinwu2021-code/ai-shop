package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import java.time.Instant;

/** Merchant collects payment; pay manages recharge facts and balances, never a platform-custody transfer. */
public interface StoredValuePort {
    RechargeView createRecharge(EntitlementContext context, CreateRecharge command);

    RechargeView submit(EntitlementContext context, SubmitRecharge command);

    RechargeView receive(EntitlementContext context, ReceiveRecharge command);

    RechargeView cancel(EntitlementContext context, CancelRecharge command);

    RechargeView credit(EntitlementContext context, CreditRecharge command);

    RechargeView queryRecharge(EntitlementContext context, String entityNo, String rechargeNo);

    BalanceView balance(EntitlementContext context, BalanceQuery query);

    CursorPage<StoreItem> stores(EntitlementContext context, StoreQuery query);

    CursorPage<UsageItem> consumptions(EntitlementContext context, ConsumptionQuery query);

    enum CollectionMethod { CASH, TRANSFER, MERCHANT_OFFLINE }

    enum RechargeState { NOT_FOUND, WAIT_COLLECTION, SUBMITTED, RECEIVED, CREDITED, CANCELLED, REJECTED }

    enum StoreActivityType { RECHARGE, CONSUMPTION }

    record CreateRecharge(SourceRef sourceRef, ParticipationPermit permit) {
        public CreateRecharge {
            ContractChecks.present(sourceRef);
            ContractChecks.present(permit);
            ContractChecks.require(sourceRef.equals(permit.sourceRef()));
            ContractChecks.require(permit.payAmountMinor() != null && permit.tierNo() != null);
        }
    }

    record SubmitRecharge(String rechargeNo, long expectedRevision, long actualAmountMinor,
                          CollectionMethod collectionMethod, String receiptRef, String receiptDigest) {
        public SubmitRecharge {
            ContractChecks.id(rechargeNo);
            ContractChecks.require(expectedRevision >= 0 && actualAmountMinor > 0);
            ContractChecks.present(collectionMethod);
            ContractChecks.id(receiptRef);
            ContractChecks.hash(receiptDigest);
        }
    }

    record ReceiveRecharge(String rechargeNo, long expectedRevision, String receiptDigest, String confirmationNote) {
        public ReceiveRecharge {
            ContractChecks.id(rechargeNo);
            ContractChecks.require(expectedRevision >= 0);
            ContractChecks.hash(receiptDigest);
            ContractChecks.require(confirmationNote == null || confirmationNote.length() <= 500);
        }
    }

    record CancelRecharge(String rechargeNo, long expectedRevision, String reason) {
        public CancelRecharge {
            ContractChecks.id(rechargeNo);
            ContractChecks.require(expectedRevision >= 0 && reason != null
                    && !reason.isBlank() && reason.length() <= 500);
        }
    }

    /** Credit follows the original received decision; received does not itself mean the credit is already complete. */
    record CreditRecharge(String rechargeNo, SourceRef sourceRef, DecisionRef decisionRef) {
        public CreditRecharge {
            ContractChecks.id(rechargeNo);
            ContractChecks.present(sourceRef);
            ContractChecks.require("PAY".equals(sourceRef.sourceSystem()) && "RECHARGE".equals(sourceRef.sourceType())
                    && rechargeNo.equals(sourceRef.sourceNo()));
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.RECHARGE_RECEIVED);
        }
    }

    record BalanceQuery(String userNo, String entityNo, String currency, String storeNo) {
        public BalanceQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.currency(currency);
            ContractChecks.id(storeNo);
        }
    }

    record StoreQuery(String userNo, String cursor, int size) {
        public StoreQuery {
            ContractChecks.id(userNo);
            EntitlementQueryPort.checkPage(cursor, size);
        }
    }

    record ConsumptionQuery(String userNo, String entityNo, String storeNo, String cursor, int size) {
        public ConsumptionQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.optionalId(storeNo);
            EntitlementQueryPort.checkPage(cursor, size);
        }
    }

    record RechargeView(String rechargeNo, RechargeState status, String userNo, String entityNo, String storeNo,
                        String currency, Long paidAmountMinor, Long creditedTotalMinor, Instant receivedAt,
                        Instant creditedAt, long revision,String customerNo,String documentNo) {
        public RechargeView(String rechargeNo,RechargeState status,String userNo,String entityNo,String storeNo,String currency,Long paidAmountMinor,Long creditedTotalMinor,Instant receivedAt,Instant creditedAt,long revision,String customerNo){this(rechargeNo,status,userNo,entityNo,storeNo,currency,paidAmountMinor,creditedTotalMinor,receivedAt,creditedAt,revision,customerNo,null);}
        public RechargeView(String rechargeNo,RechargeState status,String userNo,String entityNo,String storeNo,String currency,Long paidAmountMinor,Long creditedTotalMinor,Instant receivedAt,Instant creditedAt,long revision){this(rechargeNo,status,userNo,entityNo,storeNo,currency,paidAmountMinor,creditedTotalMinor,receivedAt,creditedAt,revision,userNo);}
    }

    record BalanceView(String accountNo, String currency, long balanceMinor, long heldMinor, long availableMinor,
                       Long applicableMinor) {
    }

    record StoreItem(String storeNo, String entityNo, String storeName, String storeStatus,
                     StoreActivityType lastActivityType, Instant lastActivityAt) {
    }
}
