package ai.neargo.shop.paybridge.dto;

import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import java.time.Instant;
import java.util.List;

/** Explicit consumer projection: no raw identity reference, authorization reference, internal command or private balance pools. */
public final class EntitlementServiceViews {
    private EntitlementServiceViews() { }
    /** Business progress may wait for merchant submission even when no pay hold exists yet. */
    public record Operation(String operationNo, String status, String holdNo, String usageNo, Instant occurredAt) {
        public static Operation from(HoldView value) {
            return new Operation(value.operationNo(), value.status().name(), value.holdNo(), value.usageNo(),
                    value.occurredAt());
        }
        public static Operation pending(String no, String status) { return new Operation(no, status, null, null, null);
        }
    }
    public record Preview(String operationNo, String orderRef, SourceRef sourceRef, PreviewView preview) { }
    public record Item(String itemNo, String title, int quantity) { }
    public record Asset(String instanceNo, String title, int unitsToUse, Integer remainingAfter) { }
    public record Confirmation(long orderRevision, String quoteRef, String selectionDigest, Instant expiresAt) { }
    public record Payment(String assetKind,String currency,long amountMinor,String scopeType,String issuerName){
        public Payment(String assetKind,String currency,long amountMinor){this(assetKind,currency,amountMinor,null,null);}
    }
    public record ServiceView(String serviceOrderNo, String operationNo, String status, String storeNo, String storeName,
            String serviceStaffName, List<Item> items, Instant serviceAt, Asset asset, Confirmation confirmation,
            Payment payment) { }
    public static ServiceView consumer(ServiceOrderSnapshot s) {
        var p = s.preview() == null ? null : s.preview().preview();
        return new ServiceView(s.serviceOrderNo(), s.operationNo(), status(s), s.fact().storeNo(), s.fact().storeName(),
                s.fact().serviceStaffName(), s.fact().items().stream().map(i -> new Item(i.itemNo(), i.title(),
                        i.quantity())).toList(), s.fact().serviceAt(),
                p == null ? null : new Asset(p.instanceNo(), p.title(), p.unitsToUse(), p.remainingAfter()),
                s.preview() == null ? null : new Confirmation(s.fact().source().sourceRevision(), s.preview().ref(),
                        s.preview().digest(), s.preview().expiresAt()),
                s.preview() == null || s.preview().moneyQuote() == null ? null
                        : new Payment("STORED_VALUE",s.fact().currency(),s.preview().amountMinor(),s.preview().moneyQuote().walletProof()==null?"STORE":s.preview().moneyQuote().walletProof().selected().scopeType(),s.preview().moneyQuote().walletProof()==null?null:s.preview().moneyQuote().walletProof().selected().issuerName()));
    }
    public static String status(ServiceOrderSnapshot s) {
        if (s.command() == null && List.of("CREATED", "PREVIEWED", "AUTHORIZED").contains(s.status())
                && (!Instant.now().isBefore(s.deadline()) || s.preview() != null
                && !Instant.now().isBefore(s.preview().expiresAt()))) return "EXPIRED";
        return switch (s.status()) {
            case "CREATED", "PREVIEWED" -> "WAIT_AUTH";
            case "AUTHORIZED" -> "WAIT_SUBMIT";
            case "PENDING", "COMMIT_DECIDED", "CANCEL_DECIDED" -> "PROCESSING";
            default -> s.status();
        };
    }
}
