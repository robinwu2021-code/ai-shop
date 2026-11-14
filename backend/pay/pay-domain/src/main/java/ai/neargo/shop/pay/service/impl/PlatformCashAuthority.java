package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformCollectionService.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Pure canonical checks shared by locked financial workflows and unlocked reporting reads. */
final class PlatformCashAuthority {
    private PlatformCashAuthority() {}
    private static void require(boolean valid,String code) { if(!valid) throw new IllegalArgumentException(code); }
    private static String string(Map<String,Object> row,String column) { return (String)row.get(column); }
    static Request receiptMetadata(JsonMapper json,Map<String,Object> row) {
        var frozen = json.readValue(string(row,"payment_request_json"), PlatformCollectionServiceImpl.Frozen.class);
        var r = frozen.request();
        String no = string(row,"payment_no");
        require(string(row,"tenant_no").equals(r.key().tenantNo()) && string(row,"order_no").equals(r.key().orderNo())
                && no.equals(PlatformCollectionServiceImpl.paymentNo(r.key()))
                && frozen.business().equals(string(row,"pay_scene"))
                && r.merchantNo().equals(string(row,"entity_no"))
                && r.amountMinor() == ((Number) row.get("amount_minor")).longValue() && r.amountMinor() > 0
                && r.currency().equals(string(row,"currency"))
                && r.channel().equals(string(row,"pay_channel"))
                && r.method().equals(string(row,"pay_method"))
                && (r.offline() == null || r.offline().merchantNo().equals(r.merchantNo())),
                "OFFLINE_CASH_METADATA_MISMATCH");
        return r;
    }
    static String bankReferenceKey(Request r,String reference) {
        return PlatformOfflineLedger.hash("platform-bank-reference:v1",r.key().tenantNo(),
                r.offline().receivingAccountKey(),reference.trim().toUpperCase(Locale.ROOT));
    }
    /** Recheck immutable proof; reading facts must never refresh a gateway or repair a ledger row. */
    static boolean receiptVerified(JsonMapper json,Request r,Map<String,Object> row) {
        if (!"SUCCESS".equals(row.get("status")) || !"PAY".equals(row.get("direction"))
                || !(row.get("succeeded_at") instanceof Number succeeded)
                || !(row.get("deleted") instanceof Number deleted) || deleted.intValue()!=0
                || !("PLATFORM_MALL".equals(row.get("pay_scene")) || "MALL_UNMATCHED".equals(row.get("pay_scene")))) return false;
        if (r.offline()!=null) {
            var p=json.readValue((String)row.get("platform_manual_proof"),OfflineProof.class);
            String bankKey=bankReferenceKey(r,p.bankReference());
            boolean unmatched="MALL_UNMATCHED".equals(row.get("pay_scene"));
            return "OFFLINE".equals(r.channel()) && "BANK_TRANSFER".equals(r.method())
                    && p.key()!=null && r.key().tenantNo().equals(p.key().tenantNo())
                    && r.key().orderNo().equals(p.key().orderNo())
                    && (unmatched ? r.key().attemptNo().equals("U"+bankKey.substring(0,60)) : r.key().equals(p.key()))
                    && r.merchantNo().equals(p.actualReceiver()) && r.amountMinor()==p.actualMinor()
                    && r.currency().equals(p.currency()) && p.currency().matches("[A-Z]{3}")
                    && p.actualPaidAt()>=0 && p.actualPaidAt()%1000==0
                    && p.confirmedAt()>=p.actualPaidAt() && succeeded.longValue()==p.actualPaidAt()
                    && ("HISTORICAL".equals(p.mode()) || "NORMAL".equals(p.mode())
                        && Math.addExact(p.actualPaidAt(),999)>=r.preparedAt())
                    && p.bankReference().equals(row.get("trade_no")) && bankKey.equals(row.get("platform_bank_ref_key"))
                    && p.evidenceReference()!=null && !p.evidenceReference().isBlank()
                    && p.operatorNo()!=null && !p.operatorNo().isBlank() && row.get("platform_dispatch_at")!=null;
        }
        var p=json.readValue((String)row.get("platform_receipt_json"),ai.neargo.shop.pay.channel.PayGateway.QueryResult.class);
        return !"OFFLINE".equals(r.channel()) && "PLATFORM_MALL".equals(row.get("pay_scene"))
                && p.ok() && p.found() && p.paid() && "SUCCESS".equals(p.providerState())
                && r.amountMinor()==p.amountMinor() && r.currency().equals(p.currency())
                && r.merchantNo().equals(p.receiverNo()) && Objects.equals(row.get("out_trade_no"),p.outTradeNo())
                && p.tradeNo()!=null && !p.tradeNo().isBlank() && p.tradeNo().equals(row.get("trade_no"))
                && p.actualPaidAt()!=null && succeeded.longValue()==p.actualPaidAt()
                && Math.addExact(p.actualPaidAt(),p.paidAtPrecisionMs()-1)>=r.preparedAt();
    }
    static boolean refundVerified(JsonMapper json,Request original,String no,Map<String,Object> row) {
        var frozen = json.readValue((String)row.get("refund_request_json"), PlatformRefundServiceImpl.Frozen.class);
        var r = frozen.request();
        long amount = ((Number)row.get("amount_minor")).longValue();
        boolean valid = PlatformRefundServiceImpl.paymentNo(r.key()).equals(row.get("payment_no"))
                && original.channel().equals(frozen.channel())
                && "PLATFORM_MALL".equals(row.get("pay_scene")) && "PLATFORM_MALL".equals(frozen.business())
                && original.key().tenantNo().equals(r.key().tenantNo())
                && original.key().orderNo().equals(row.get("order_no"))
                && original.key().orderNo().equals(r.key().orderNo())
                && original.key().attemptNo().equals(r.originalAttemptNo()) && no.equals(r.originalPaymentNo())
                && original.merchantNo().equals(r.merchantNo()) && r.merchantNo().equals(row.get("entity_no"))
                && original.currency().equals(r.currency()) && r.currency().equals(row.get("currency"))
                && original.amountMinor() == r.originalPaidMinor() && amount == r.refundMinor() && amount > 0
                && "CONSUMED".equals(row.get("refund_capacity_state")) && "SUCCEEDED".equals(row.get("refund_query_state"))
                && row.get("refund_verified_minor") instanceof Number verified && verified.longValue() == amount
                && row.get("refund_verified_at") instanceof Number at && at.longValue() > 0
                && row.get("refund_verified_provider_no") instanceof String provider && !provider.isBlank();
        if ("OFFLINE".equals(original.channel())) {
            var proof = json.readValue((String)row.get("platform_manual_proof"), ai.neargo.shop.pay.service.PlatformRefundService.OfflinePayout.class);
            valid &= r.key().equals(proof.key()) && amount == proof.actualMinor()
                    && r.currency().equals(proof.currency()) && proof.paidOutAt() > 0
                    && proof.confirmedAt() >= proof.paidOutAt()
                    && proof.bankReference().equals(row.get("refund_verified_provider_no"))
                    && proof.bankReference().equals(row.get("trade_no"))
                    && row.get("refund_verified_at") instanceof Number verifiedAt && verifiedAt.longValue() == proof.paidOutAt()
                    && row.get("succeeded_at") instanceof Number succeeded && succeeded.longValue() == proof.paidOutAt()
                    && proof.evidenceReference() != null && !proof.evidenceReference().isBlank()
                    && proof.payeeReference() != null && !proof.payeeReference().isBlank()
                    && PlatformRefundServiceImpl.bankKey(r.key().tenantNo(),proof.bankReference()).equals(row.get("platform_bank_ref_key"));
        } else {
            var proof = json.readValue((String)row.get("platform_receipt_json"), ai.neargo.shop.spi.pay.RefundQueryResult.class);
            valid &= proof.status() == ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED
                    && amount == proof.amountMinor() && r.currency().equals(proof.currency())
                    && row.get("out_trade_no").equals(proof.outRefundNo())
                    && Objects.equals(frozen.originalExternal(),proof.originalOutTradeNo())
                    && Objects.equals(frozen.originalProvider(),proof.originalTradeNo())
                    && Objects.equals(r.originalPaidMinor(),proof.originalTotalMinor())
                    && r.merchantNo().equals(proof.receiverNo())
                    && Objects.equals(row.get("refund_verified_provider_no"),proof.providerNo())
                    && proof.actualRefundAt() != null && proof.actualRefundAt() > 0
                    && proof.observedAt() >= proof.actualRefundAt()
                    && row.get("refund_verified_at") instanceof Number verifiedAt && verifiedAt.longValue() == proof.observedAt()
                    && row.get("succeeded_at") instanceof Number succeeded && succeeded.longValue() == proof.actualRefundAt();
        }
        return valid;
    }
}
