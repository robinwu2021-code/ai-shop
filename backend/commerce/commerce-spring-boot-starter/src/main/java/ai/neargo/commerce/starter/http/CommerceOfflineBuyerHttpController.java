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
 * Merchant side of offline (bank transfer) purchase. Identity and store come from the host
 * context, never from JSON. A declaration never creates money.
 */
@RestController
public final class CommerceOfflineBuyerHttpController extends MallHttpSupport {
    private final OfflinePurchaseFacade offline;

    public CommerceOfflineBuyerHttpController(
            OfflinePurchaseFacade offline,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.offline = Objects.requireNonNull(offline);
    }


    @GetMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/cash-refunds")
    public Object cashRefunds(@PathVariable("storeNo") String store,@PathVariable("orderNo") String order){var a=context.buyer(store,"commerce:mall:read");return ok(offline.cashRefunds(a.actor(),new OrderQuery(a.store(),code(order))));}

    public record QuoteRequest(List<QuoteLine> lines, String currency, String collectionMethod) {
        public QuoteRequest(List<QuoteLine> lines,String currency) { this(lines,currency,null); }
    }

    @PostMapping("/biz/stores/{storeNo}/mall/offline/quotes")
    public Object quote(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, QuoteRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(offline.quote(c.actor(), new QuoteCommand(c.store(), d.lines(), d.currency()),d.collectionMethod()));
    }

    public record UpgradeRequest(String targetPlanVersionNo, String operationNo,
            long expectedEntitlementVersion, String targetSkuVersionNo, String currency, String collectionMethod) {}

    @PostMapping("/biz/stores/{storeNo}/mall/offline/upgrade-quotes")
    public Object upgrade(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, UpgradeRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(offline.quoteUpgrade(c.actor(), new UpgradeCommand(c.store(), d.targetPlanVersionNo(),
                d.operationNo(), d.expectedEntitlementVersion(), d.targetSkuVersionNo()), d.currency(),d.collectionMethod()));
    }

    public record EmptyRequest() {}

    @PostMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions")
    public Object issue(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        json.read(body, EmptyRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(offline.issue(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    @GetMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions")
    public Object instructions(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(offline.instructions(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    @PostMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence")
    public Object reserve(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        json.read(body, EmptyRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(
                offline.reserveEvidence(
                        c.actor(), new OrderQuery(c.store(), code(order)), "BUYER_RECEIPT"));
    }

    @PostMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence/content")
    public Object uploadBuyer(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestParam("assetKey") String key,
            @RequestHeader(value = "Content-Type", required = false) String type,
            @RequestBody byte[] body) {
        var c = context.buyer(store, "commerce:mall:purchase");
        offline.upload(
                c.actor(), new OrderQuery(c.store(), code(order)), assetKey(key), upload(body, type));
        return ok(Map.of("assetKey", key));
    }

    @GetMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence/url")
    public Object readBuyer(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestParam("assetKey") String key) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(
                Map.of(
                        "url",
                        offline.evidence(
                                c.actor(), new OrderQuery(c.store(), code(order)), assetKey(key))));
    }

    public record ClaimRequest(
            String claimNo,
            Money declaredAmount,
            Instant declaredPaidAt,
            String bankReference,
            List<String> evidenceKeys) {}

    @PostMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims")
    public Object claim(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        var d = json.read(body, ClaimRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(
                offline.submit(
                        c.actor(),
                        new ClaimCommand(
                                new OrderQuery(c.store(), code(order)),
                                d.claimNo(),
                                d.declaredAmount(),
                                d.declaredPaidAt(),
                                d.bankReference(),
                                d.evidenceKeys())));
    }

    @GetMapping("/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims")
    public Object claims(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(offline.claims(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    }
