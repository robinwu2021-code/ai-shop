package ai.neargo.shop.spi.entitlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Unified usage protocol delegates money to stored value and units to rights, without a cross-domain transaction. */
public interface EntitlementUsePort {
    HoldView prepare(EntitlementContext context, UseCommand command);

    UseReceipt commit(EntitlementContext context, UseDecision command);

    HoldView release(EntitlementContext context, CancelUse command);

    UseReceipt reverse(EntitlementContext context, OrderBenefitReverse command);

    /** Lost responses are resolved using the original operationNo. */
    HoldView query(EntitlementContext context, String entityNo, String operationNo);

    enum AssetKind { ORDER_COUPON, STORE_EXCHANGE, PAID_CARD, STORED_VALUE }

    enum State { NOT_FOUND, WAIT_AUTH, HELD, COMMITTED, RELEASED, PROCESSING, EXCEPTION }

    record UseItem(String orderLineNo, String itemNo, String title, int quantity, long amountMinor) {
        public UseItem {
            ContractChecks.id(orderLineNo);
            ContractChecks.id(itemNo);
            ContractChecks.require(title != null && !title.isBlank() && title.length() <= 200);
            ContractChecks.require(quantity > 0 && amountMinor >= 0);
        }
    }

    record UseCommand(String operationNo, SourceRef sourceRef, String userNo, String entityNo, String storeNo,
                      String currency, AssetKind assetKind, String instanceNo, String accountNo, LocalDate serviceDay,
                      Integer units, Long amountMinor, List<UseItem> itemsSnapshot, String authorizationRef,
                      DecisionRef decisionRef) {
        public UseCommand {
            ContractChecks.id(operationNo);
            ContractChecks.present(sourceRef);
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            ContractChecks.present(assetKind);
            ContractChecks.id(authorizationRef);
            itemsSnapshot = List.copyOf(ContractChecks.present(itemsSnapshot));
            ContractChecks.require(!itemsSnapshot.isEmpty());
            if (assetKind == AssetKind.STORED_VALUE) {
                ContractChecks.id(accountNo);
                ContractChecks.require(instanceNo == null && units == null && amountMinor != null && amountMinor > 0);
            } else {
                ContractChecks.id(instanceNo);
                ContractChecks.require(accountNo == null && units != null && units > 0);
                ContractChecks.require(amountMinor == null
                        || (assetKind == AssetKind.ORDER_COUPON && amountMinor >= 0));
                if (assetKind == AssetKind.ORDER_COUPON) {
                    ContractChecks.require(units == 1);
                }
            }
            if (decisionRef != null) {
                decisionRef.requireFor(sourceRef, DecisionRef.DecisionType.USE_COMMIT);
            }
        }
    }

    record UseDecision(String operationNo, SourceRef sourceRef, DecisionRef decisionRef) {
        public UseDecision {
            ContractChecks.id(operationNo);
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.USE_COMMIT);
        }
    }

    record CancelUse(String operationNo, SourceRef sourceRef, DecisionRef decisionRef) {
        public CancelUse {
            ContractChecks.id(operationNo);
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.USE_CANCEL);
        }
    }

    record OrderBenefitReverse(String usageNo, String reverseNo, SourceRef sourceRef, AssetKind assetKind,
                               Long amountMinor, DecisionRef decisionRef) {
        public OrderBenefitReverse {
            ContractChecks.id(usageNo);
            ContractChecks.id(reverseNo);
            ContractChecks.require(assetKind == AssetKind.ORDER_COUPON && amountMinor != null && amountMinor > 0);
            ContractChecks.present(decisionRef).requireFor(sourceRef, DecisionRef.DecisionType.ORDER_REVERSE);
        }
    }

    record HoldView(String operationNo, State status, String holdNo, String usageNo, Instant occurredAt) {
        public HoldView {
            ContractChecks.id(operationNo);
            ContractChecks.present(status);
            ContractChecks.optionalId(holdNo);
            ContractChecks.optionalId(usageNo);
            if (status == State.COMMITTED) {
                ContractChecks.id(usageNo);
            }
        }
    }

    record UseReceipt(String operationNo, State status, String usageNo, Instant occurredAt) {
        public UseReceipt {
            ContractChecks.id(operationNo);
            ContractChecks.present(status);
            ContractChecks.require(status == State.COMMITTED
                    || status == State.PROCESSING || status == State.EXCEPTION);
            if (status == State.COMMITTED) {
                ContractChecks.id(usageNo);
                ContractChecks.present(occurredAt);
            }
        }
    }
}
