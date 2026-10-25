package ai.neargo.shop.spi.trade;

import java.math.BigDecimal;
import java.util.List;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Scoped original-source pickup verification; payment authorization and code assignment remain original-owner facts. */
public interface IndustryFulfillmentPort {
    record Reference(Scope scope,PricingSourceRef source,String orderNo,String subOrderNo) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    record VerifyPickup(Reference reference,String actorNo,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) long expectedVersion,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String verifyCode) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
        @Override public String toString() { return "IndustryPickupVerification[REDACTED]"; }
    }
    record ItemTarget(Long orderItemId,String sourceComponentRef,BigDecimal cumulativeQty,String unit) { }
    record Fulfill(Reference reference,String actorNo,long expectedVersion,String kind,String sourceOperationRef,
            String planHash,String verifyCode,String recipientNo,String recipientRef,List<ItemTarget> lines) {
        public Fulfill {lines=List.copyOf(lines);}
        @Override public String toString() {return "IndustryPartialFulfillment[REDACTED]";}
    }
    record FulfilledLine(Long orderItemId,String sourceComponentRef,BigDecimal fulfilledQty,String unit) { }
    record Receipt(String fulfillmentNo,Reference reference,String status,String subOrderStatus,
            boolean requireBuyerConfirm,boolean pending,String inventoryReservationNo,String inventoryState,
            int inventoryLifecycleVersion,long subOrderVersion,List<FulfilledLine> lines) {
        public Receipt {lines=List.copyOf(lines);}
        public Receipt(String no,Reference ref,String status,String subStatus,boolean buyer,boolean pending,String reservation,String inventory,int lifecycle,long version) {
            this(no,ref,status,subStatus,buyer,pending,reservation,inventory,lifecycle,version,List.of());
        }
    }
    default Receipt fulfill(Fulfill command,String key) {throw new UnsupportedOperationException("Partial fulfillment unavailable");}
    Receipt verifyPickup(VerifyPickup command,String key);
    /** Query reconciles original issue evidence; it never creates a fresh inventory issue command. */
    Receipt query(Reference reference,String actorNo,String key);
}
