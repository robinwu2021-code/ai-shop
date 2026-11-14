package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import java.math.BigDecimal;

/** Trusted source adapter; identity and physical postings cannot be supplied by an App. */
public interface StockReplenishmentSourcePort {
    record Outbound(String sourceSystem,String sourceOwner,String documentNo,int lineNo,String itemNo,
                    String baseUnit,BigDecimal quantity,String targetStoreNo,long postingSequence) {
        public Outbound(String sourceSystem,String sourceOwner,String documentNo,int lineNo,String itemNo,String baseUnit,BigDecimal quantity,String targetStoreNo){this(sourceSystem,sourceOwner,documentNo,lineNo,itemNo,baseUnit,quantity,targetStoreNo,0);}
    }
    record Checkpoint(String sourceSystem,String sourceOwner,long postingSequence) {}
    record Inbound(String sourceSystem,String sourceOwner,String documentNo,int lineNo,String itemNo,
                   String baseUnit,BigDecimal quantity,String sourceRef) {}
    Checkpoint checkpoint(Scope scope,String actor);
    Outbound outbound(Scope scope,String actor,String documentNo,int lineNo);
    Inbound inbound(Scope scope,String actor,String documentNo,int lineNo);
}
