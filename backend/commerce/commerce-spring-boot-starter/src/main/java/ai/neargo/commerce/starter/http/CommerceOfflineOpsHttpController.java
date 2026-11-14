package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade.*;
import ai.neargo.commerce.api.port.PrivateEvidencePort;

import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

/**
 * Operator side of offline (bank transfer) purchase: collection profile, review queue, assisted
 * orders, evidence, decline/confirm/void and refund payouts. Only the confirmation records money.
 */
@RestController
public final class CommerceOfflineOpsHttpController extends MallHttpSupport {
    private final OfflinePurchaseFacade offline;
    private final MallFacade mall;

    public CommerceOfflineOpsHttpController(
            OfflinePurchaseFacade offline,
            MallFacade mall,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.offline = Objects.requireNonNull(offline);
        this.mall = Objects.requireNonNull(mall);
    }


    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/recover")
    public Object recoverOriginal(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order){var a=context.operator("commerce:mall:order:read");offline.resume(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order)));return ok(Map.of("recovered",true));}
    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/cash-receipts")
    public Object cashReceipts(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order){var a=context.operator("commerce:mall:order:read");return ok(offline.cashReceipts(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order))));}
    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/cash-refunds")
    public Object cashRefunds(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order){var a=context.operator("commerce:mall:order:read");return ok(offline.cashRefunds(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order))));}
    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/cash-refunds")
    public Object requestCashRefund(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order,@RequestBody String body){var a=context.operator("commerce:mall:offline:refund:confirm");var c=json.read(body,ai.neargo.commerce.api.port.OfflineCashRefundPort.Request.class);return ok(offline.requestCashRefund(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order)),c));}
    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/cash-refunds/{refundNo}/payout")
    public Object payoutCashRefund(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order,@PathVariable("refundNo") String refund,@RequestBody String body){var a=context.operator("commerce:mall:offline:refund:confirm");var c=json.read(body,ai.neargo.commerce.api.port.OfflineCashRefundPort.Payout.class);return ok(offline.payoutCashRefund(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order)),code(refund),c));}
    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/cash-refunds/{refundNo}/recover")
    public Object recoverCashRefund(@RequestParam("storeNo") String store,@PathVariable("orderNo") String order,@PathVariable("refundNo") String refund){var a=context.operator("commerce:mall:offline:refund:confirm");return ok(offline.recoverCashRefund(a,new OrderQuery(new StoreRef(a.tenantNo(),code(store)),code(order)),code(refund)));}

    public record QuoteRequest(List<QuoteLine> lines, String currency, String collectionMethod) {
        public QuoteRequest(List<QuoteLine> lines,String currency) { this(lines,currency,null); }
    }
    public record EmptyRequest() {}

    @GetMapping("/ops/biz-mall/offline/profile")
    public Object profile(@RequestParam("currency") String currency,
            @RequestParam(value="collectionMethod",required=false) String method) {
        var actor = context.operator("commerce:collection:profile:read");
        return ok((method==null?offline.profile(actor,currency):offline.profile(actor, currency, method)).orElse(null));
    }

    @PutMapping("/ops/biz-mall/offline/profile")
    public Object configure(@RequestBody String body) {
        var d = json.read(body, ProfileCommand.class);
        var actor = context.operator("commerce:collection:profile:write");
        return ok(offline.configureProfile(actor, d));
    }

    @GetMapping("/ops/biz-mall/offline/claims")
    public Object pending(
            @RequestParam(value = "afterOrderNo", required = false) String afterOrder,
            @RequestParam(value = "afterClaimNo", required = false) String afterClaim,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        var actor = context.operator("commerce:mall:offline:review");
        return ok(
                offline.pendingClaims(
                        actor,
                        afterOrder == null ? null : code(afterOrder),
                        afterClaim == null ? null : code(afterClaim),
                        limit));
    }

    @PostMapping("/ops/biz-mall/offline/quotes")
    public Object assistedQuote(@RequestParam("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, QuoteRequest.class);
        var actor = context.operator("commerce:mall:assisted:purchase");
        return ok(
                offline.quote(
                        actor,
                        new QuoteCommand(
                                new StoreRef(actor.tenantNo(), code(store)), d.lines(), d.currency()),d.collectionMethod()));
    }

    public record PlaceRequest(
            String quoteNo, String idempotencyKey, ShippingAddress shippingAddress) {}

    @PostMapping("/ops/biz-mall/offline/orders")
    public Object assistedPlace(@RequestParam("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, PlaceRequest.class);
        var actor = context.operator("commerce:mall:assisted:purchase");
        return ok(
                mall.placeOrder(
                        actor,
                        new PlaceOrderCommand(
                                d.quoteNo(),
                                d.idempotencyKey(),
                                Optional.ofNullable(d.shippingAddress()),
                                new StoreRef(actor.tenantNo(), code(store)))));
    }

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/instructions")
    public Object assistedIssue(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        json.read(body, EmptyRequest.class);
        var actor = context.operator("commerce:mall:assisted:purchase");
        return ok(offline.issue(actor, opsOrder(actor, store, order)));
    }

    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/instructions")
    public Object opsInstructions(
            @PathVariable("orderNo") String order, @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(offline.instructions(actor, opsOrder(actor, store, order)));
    }

    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/claims")
    public Object opsClaims(
            @PathVariable("orderNo") String order, @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(offline.claims(actor, opsOrder(actor, store, order)));
    }

    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/actions/{actionNo}")
    public Object action(
            @PathVariable("orderNo") String order,
            @PathVariable("actionNo") String action,
            @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(offline.action(actor, opsOrder(actor, store, order), code(action)).orElse(null));
    }

    public record SupplementRequest(String actionNo,String confirmationActionNo,String note,List<String> evidenceKeys) {}
    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/receipt-documentation")
    public Object documentation(@PathVariable("orderNo") String order,@RequestParam("storeNo") String store) {
        var a=context.operator("commerce:mall:order:read");return ok(offline.documentation(a,opsOrder(a,store,order)));
    }
    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/receipt-documentation")
    public Object supplement(@PathVariable("orderNo") String order,@RequestParam("storeNo") String store,@RequestBody String body) {
        var a=context.operator("commerce:mall:offline:confirm");var d=json.read(body,SupplementRequest.class);
        return ok(offline.supplement(a,new SupplementCommand(opsOrder(a,store,order),d.actionNo(),d.confirmationActionNo(),d.note(),d.evidenceKeys())));
    }
    public record EvidenceRequest(String purpose) {}

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/evidence")
    public Object opsReserve(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, EvidenceRequest.class);
        var actor = context.operator("commerce:mall:offline:evidence");
        return ok(offline.reserveEvidence(actor, opsOrder(actor, store, order), d.purpose()));
    }

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/evidence/content")
    public Object opsUpload(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestParam("assetKey") String key,
            @RequestHeader(value = "Content-Type", required = false) String type,
            @RequestBody byte[] body) {
        var actor = context.operator("commerce:mall:offline:evidence");
        offline.upload(actor, opsOrder(actor, store, order), assetKey(key), upload(body, type));
        return ok(Map.of("assetKey", key));
    }

    @GetMapping("/ops/biz-mall/offline/orders/{orderNo}/evidence/url")
    public Object opsRead(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestParam("assetKey") String key) {
        var actor = context.operator("commerce:mall:offline:evidence");
        return ok(Map.of("url", offline.evidence(actor, opsOrder(actor, store, order), assetKey(key))));
    }

    public record DeclineRequest(String reason) {}

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/claims/{claimNo}/decline")
    public Object decline(
            @PathVariable("orderNo") String order,
            @PathVariable("claimNo") String claim,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, DeclineRequest.class);
        var actor = context.operator("commerce:mall:offline:review");
        return ok(
                offline.decline(
                        actor, new DeclineCommand(opsOrder(actor, store, order), code(claim), d.reason())));
    }

    public record ConfirmRequest(
            String actionNo,
            String claimNo,
            Money actualAmount,
            Instant actualPaidAt,
            String actualReceiver,
            String bankReference,
            String evidenceKey,
            boolean historical,
            String reason, String collectorNo, String collectionReference,boolean testPayment) {}

    @GetMapping("/ops/biz-mall/offline/collectors")
    public Object collectors(@RequestParam("storeNo") String store) {
        var actor=context.operator("commerce:mall:offline:confirm");
        return ok(offline.collectors(actor,new StoreRef(actor.tenantNo(),code(store))));
    }

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/confirmations")
    public Object confirm(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, ConfirmRequest.class);
        var actor = context.operator("commerce:mall:offline:confirm");
        return ok(
                offline.confirm(
                        actor,
                        new ReviewCommand(
                                opsOrder(actor, store, order),
                                d.actionNo(),
                                d.claimNo(),
                                d.actualAmount(),
                                d.actualPaidAt(),
                                d.actualReceiver(),
                                d.bankReference(),
                                d.evidenceKey(),
                                d.historical(),
                                d.reason(),d.collectorNo(),d.collectionReference(),d.testPayment())));
    }

    public record VoidRequest(
            String actionNo,
            String reconciliationReference,
            String reason,
            String evidenceKey,
            Map<String, String> unmatchedDispositions) {}

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/voids")
    public Object voidOrder(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, VoidRequest.class);
        var actor = context.operator("commerce:mall:offline:void");
        return ok(
                offline.voidOrder(
                        actor,
                        new VoidCommand(
                                opsOrder(actor, store, order),
                                d.actionNo(),
                                d.reconciliationReference(),
                                d.reason(),
                                d.evidenceKey(),
                                d.unmatchedDispositions())));
    }

    public record PayoutRequest(
            Money actualAmount,
            Instant paidOutAt,
            String payeeReference,
            String bankReference,
            String evidenceKey) {}

    @PostMapping("/ops/biz-mall/offline/orders/{orderNo}/refunds/{refundNo}/payouts")
    public Object payout(
            @PathVariable("orderNo") String order,
            @PathVariable("refundNo") String refund,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, PayoutRequest.class);
        var actor = context.operator("commerce:mall:offline:refund:confirm");
        return ok(
                offline.confirmRefundPayout(
                        actor,
                        new RefundPayoutCommand(
                                opsOrder(actor, store, order),
                                code(refund),
                                d.actualAmount(),
                                d.paidOutAt(),
                                d.payeeReference(),
                                d.bankReference(),
                                d.evidenceKey())));
    }
}
