package ai.neargo.inventory.api.replenishment;

import java.math.BigDecimal;

/** Immutable posted document facts; never a mirror, reservation or second posting. */
public final class ReplenishmentContracts {
    private ReplenishmentContracts() {}
    public record Outbound(String ownerId, String documentNo, int lineNo, String itemId,
                           String baseUnit, BigDecimal quantity, String targetStoreNo,long postingSequence) {
        public Outbound(String ownerId,String documentNo,int lineNo,String itemId,String baseUnit,BigDecimal quantity,String targetStoreNo){this(ownerId,documentNo,lineNo,itemId,baseUnit,quantity,targetStoreNo,0);}
    }
    public record Checkpoint(String ownerId,long postingSequence) {}
    public record Inbound(String ownerId, String documentNo, int lineNo, String itemId,
                          String baseUnit, BigDecimal quantity, String sourceRef) {}
}
