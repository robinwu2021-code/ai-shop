package ai.neargo.shop.pay.points.model;

/** A reference to verified cash; a bound refund receipt cannot finance an unrelated budget action. */
public record EvidenceRef(String evidenceNo,String currency,long externalMinor,String purpose,String sourceRef) {
    /** General incoming treasury receipts have no prior business allocation. */
    public EvidenceRef(String evidenceNo,String currency,long externalMinor) {
        this(evidenceNo,currency,externalMinor,null,null);
    }
}
