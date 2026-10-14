package ai.neargo.shop.pay.points.model;

/** Text, settlement mode and recipient path are frozen into each accepted hold. */
public record AgreementSnapshot(String agreementNo,String typeCode,String merchantNo,String storeNo,
                                String signedBy,String termsVersion,String termsHash,String termsText,
                                String settleMode,String path,String channel,String accountVersion,String state) { }
