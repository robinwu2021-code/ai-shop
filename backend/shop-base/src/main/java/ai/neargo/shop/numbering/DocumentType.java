package ai.neargo.shop.numbering;

import java.util.HashSet;

/** Human-facing document namespace. Internal BizKey, tokens and channel numbers are separate. */
public enum DocumentType {
    SV_RECHARGE("PR", DocumentOwner.PAY), SV_VOUCHER("SV", DocumentOwner.PAY),
    ENTITLEMENT_USAGE("EU", DocumentOwner.PAY), ENTITLEMENT_GRANT("EGD", DocumentOwner.PAY),
    ENTITLEMENT_ISSUE_BATCH("EGB", DocumentOwner.PAY), POINTS_VOUCHER("PTV", DocumentOwner.PAY),
    LEGACY_COUPON_ISSUE("LCI", DocumentOwner.PAY), ENTITLEMENT_MIGRATION_PLAN("MGP", DocumentOwner.PAY),
    ENTITLEMENT_MIGRATION_IMPORT("MGI", DocumentOwner.PAY), ENTITLEMENT_MIGRATION_STAGE("MGS", DocumentOwner.PAY),
    ENTITLEMENT_RECON_RUN("ERC", DocumentOwner.PAY), ENTITLEMENT_RECON_DIFF("ERD", DocumentOwner.PAY),
    ENTITLEMENT_EXCEPTION("EEX", DocumentOwner.PAY), ENTITLEMENT_EXCEPTION_ACTION("EAC", DocumentOwner.PAY),
    INVENTORY_SYNC("ISY", DocumentOwner.CORE), INVENTORY_ALIGNMENT("IAL", DocumentOwner.CORE),
    MARKETING_ACTIVITY("PT", DocumentOwner.CORE), MARKETING_COUPON_TEMPLATE("PC", DocumentOwner.CORE),
    MARKETING_COUPON_ISSUE("PI", DocumentOwner.CORE), MARKETING_USER_COUPON("PUC", DocumentOwner.CORE),
    TRADE_ORDER("OD", DocumentOwner.CORE), TRADE_SUBORDER("OS", DocumentOwner.CORE),
    PAYMENT_ATTEMPT("PY", DocumentOwner.PAY), AFTER_SALE("AS", DocumentOwner.CORE),
    TRADE_INVOICE_REQUEST("IRQ", DocumentOwner.CORE), WITHDRAWAL("WD", DocumentOwner.PAY),
    SETTLEMENT_BATCH("STB", DocumentOwner.PAY), SETTLEMENT_BILL("STS", DocumentOwner.PAY),
    SETTLEMENT_INVOICE_REQUEST("SIR", DocumentOwner.PAY),
    SV_SETTLEMENT_BILL("VSB", DocumentOwner.PAY), SV_SETTLEMENT_PAYMENT("VSP", DocumentOwner.PAY),
    SV_SETTLEMENT_EVENT("VSE", DocumentOwner.PAY),
    BRAND_CHANGE("BRC", DocumentOwner.CORE);

    private final String prefix;
    private final DocumentOwner owner;
    DocumentType(String prefix, DocumentOwner owner) { this.prefix=prefix; this.owner=owner; }
    public String prefix() { return prefix; }
    public DocumentOwner owner() { return owner; }
    public int version() { return 1; }
    public int minimumSequenceWidth() { return 6; }
    static {
        var prefixes=new HashSet<String>();
        for(var type:values()) {
            if(!type.prefix.matches("[A-Z]{2,3}") || !prefixes.add(type.prefix))
                throw new IllegalStateException("Duplicate or invalid document prefix: "+type);
        }
    }
}
