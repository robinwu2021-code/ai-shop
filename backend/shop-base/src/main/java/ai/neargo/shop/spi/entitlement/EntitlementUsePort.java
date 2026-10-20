package ai.neargo.shop.spi.entitlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Unified usage protocol delegates money to stored value and units to rights, without a cross-domain transaction. */
public interface EntitlementUsePort {
    /** Core supplies the trusted post-activity order snapshot; quoting never reserves rights. */
    QuoteView quote(EntitlementContext context, QuoteCommand command);

    /** Resolved holder/asset identity only; raw member codes never enter this record. */
    PreviewView preview(EntitlementContext context, PreviewQuery query);

    HoldView prepare(EntitlementContext context, UseCommand command);

    UseReceipt commit(EntitlementContext context, UseDecision command);

    HoldView release(EntitlementContext context, CancelUse command);

    UseReceipt reverse(EntitlementContext context, OrderBenefitReverse command);

    /** Dedicated namespace: a reverseNo may not be interpreted as an ordinary use operationNo. */
    ReversalView queryReverse(EntitlementContext context, String entityNo, String reverseNo);

    /** Lost responses are resolved using the original operationNo. */
    HoldView query(EntitlementContext context, String entityNo, String operationNo);

    enum AssetKind { ORDER_COUPON, STORE_EXCHANGE, PAID_CARD, STORED_VALUE }

    enum State { NOT_FOUND, WAIT_AUTH, HELD, COMMITTED, RELEASED, PROCESSING, EXCEPTION }

    enum PreviewReason { OK, NOT_ACTIVE, NOT_STARTED, EXPIRED, FROZEN, INSUFFICIENT_UNITS, OUT_OF_SCOPE }

    record QuoteCommand(SourceRef sourceRef, String userNo, String entityNo, String storeNo, String currency,
                        long goodsAmountMinor, long freightMinor, List<UseItem> itemsSnapshot,
                        List<String> selectedInstanceNos, String orderSnapshotHash) {
        public QuoteCommand {
            ContractChecks.present(sourceRef);
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            ContractChecks.hash(orderSnapshotHash);
            itemsSnapshot = List.copyOf(ContractChecks.present(itemsSnapshot));
            selectedInstanceNos = List.copyOf(ContractChecks.present(selectedInstanceNos));
            ContractChecks.require(!itemsSnapshot.isEmpty());
            selectedInstanceNos.forEach(ContractChecks::id);
            ContractChecks.require(selectedInstanceNos.stream().distinct().count() == selectedInstanceNos.size());
            ContractChecks.require(itemsSnapshot.stream().map(UseItem::orderLineNo).distinct().count()
                    == itemsSnapshot.size());
            long sum = 0;
            for (var item : itemsSnapshot) {
                sum = ContractChecks.addAmounts(sum, item.amountMinor());
            }
            ContractChecks.require(sum == goodsAmountMinor);
            ContractChecks.addAmounts(goodsAmountMinor, freightMinor);
        }
    }

    record CouponAllocation(String instanceNo, String orderLineNo, long discountMinor) {
        public CouponAllocation {
            ContractChecks.id(instanceNo);
            ContractChecks.id(orderLineNo);
            ContractChecks.require(discountMinor > 0);
        }
    }

    record QuoteView(String quoteRef, SourceRef sourceRef, String selectionDigest, long discountMinor,
                     String currency, List<CouponAllocation> allocations, Instant expiresAt) {
        public QuoteView {
            ContractChecks.id(quoteRef);
            ContractChecks.present(sourceRef);
            ContractChecks.hash(selectionDigest);
            ContractChecks.currency(currency);
            ContractChecks.present(expiresAt);
            allocations = List.copyOf(ContractChecks.present(allocations));
            ContractChecks.require(allocations.stream().map(a -> List.of(a.instanceNo(), a.orderLineNo()))
                    .distinct().count() == allocations.size());
            long sum = 0;
            for (var allocation : allocations) {
                sum = ContractChecks.addAmounts(sum, allocation.discountMinor());
            }
            ContractChecks.require(discountMinor >= 0 && sum == discountMinor);
        }
    }

    record PreviewQuery(String userNo, String entityNo, String storeNo, String currency, String instanceNo,
                        LocalDate serviceDay, int units, SourceRef sourceRef) {
        public PreviewQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            ContractChecks.id(instanceNo);
            ContractChecks.require(units > 0);
        }
    }

    record PreviewView(String previewRef, String instanceNo, String title, String maskedHolder, boolean redeemable,
                       PreviewReason reason, int unitsToUse, Integer remainingAfter, long assetRevision,
                       String snapshotHash, Instant expiresAt) {
        public PreviewView {
            ContractChecks.id(previewRef);
            ContractChecks.id(instanceNo);
            ContractChecks.require(title != null && !title.isBlank() && title.length() <= 100);
            ContractChecks.present(reason);
            ContractChecks.require(redeemable == (reason == PreviewReason.OK));
            ContractChecks.require(unitsToUse > 0 && assetRevision >= 0);
            ContractChecks.require(remainingAfter == null || remainingAfter >= 0);
            ContractChecks.hash(snapshotHash);
            ContractChecks.present(expiresAt);
        }
    }

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
                      DecisionRef decisionRef, long expectedAssetRevision, String previewRef, String quoteRef) {
        public UseCommand {
            ContractChecks.id(operationNo);
            ContractChecks.present(sourceRef);
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            ContractChecks.present(assetKind);
            ContractChecks.id(authorizationRef);
            ContractChecks.require(expectedAssetRevision >= 0);
            ContractChecks.optionalId(previewRef);
            ContractChecks.optionalId(quoteRef);
            switch (assetKind) {
                case ORDER_COUPON -> {
                    ContractChecks.id(quoteRef);
                    ContractChecks.require(previewRef == null);
                }
                case STORE_EXCHANGE, PAID_CARD -> {
                    ContractChecks.id(previewRef);
                    ContractChecks.require(quoteRef == null);
                }
                case STORED_VALUE -> ContractChecks.require(previewRef == null && quoteRef == null);
            }
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

    record ReversalView(String reverseNo, State status, String usageNo, Instant occurredAt) {
        public ReversalView {
            ContractChecks.id(reverseNo);
            ContractChecks.require(status == State.NOT_FOUND || status == State.COMMITTED);
            if (status == State.COMMITTED) {
                ContractChecks.id(usageNo);
                ContractChecks.present(occurredAt);
            } else {
                ContractChecks.require(usageNo == null && occurredAt == null);
            }
        }
    }

    record HoldView(String operationNo, State status, String holdNo, String usageNo, Instant occurredAt, String documentNo) {
        public HoldView(String operationNo, State status, String holdNo, String usageNo, Instant occurredAt) { this(operationNo, status, holdNo, usageNo, occurredAt, null); }
        public HoldView {
            ContractChecks.id(operationNo);
            ContractChecks.present(status);
            ContractChecks.optionalId(holdNo);
            ContractChecks.optionalId(usageNo);
            if (status == State.HELD || status == State.COMMITTED) {
                ContractChecks.id(holdNo);
                ContractChecks.present(occurredAt);
            }
            if (status == State.COMMITTED) {
                ContractChecks.id(usageNo);
            }
        }
    }

    record UseReceipt(String operationNo, State status, String usageNo, Instant occurredAt, String documentNo) {
        public UseReceipt(String operationNo, State status, String usageNo, Instant occurredAt) { this(operationNo, status, usageNo, occurredAt, null); }
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
