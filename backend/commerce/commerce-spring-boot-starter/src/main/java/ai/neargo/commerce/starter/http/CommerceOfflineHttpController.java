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
 * Offline (bank transfer) purchase HTTP. Identity, store and actor always come from the host
 * context, never from JSON. Declarations never create money; only the ops confirmation does.
 */
@RestController
public final class CommerceOfflineHttpController extends MallHttpSupport {
    static final String BIZ = "/biz/stores/{storeNo}/mall/offline";
    static final String OPS = "/ops/biz-mall/offline";
    private static final int MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;

    private final OfflinePurchaseFacade offline;
    private final MallFacade mall;

    public CommerceOfflineHttpController(
            OfflinePurchaseFacade offline,
            MallFacade mall,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.offline = Objects.requireNonNull(offline);
        this.mall = Objects.requireNonNull(mall);
    }

    static String assetKey(String value) {
        if (value == null || value.length() > 255 || value.contains("..")
                || !value.matches("[A-Za-z0-9_\\-/]+\\.(jpg|jpeg|png)"))
            throw new MallException("INVALID_REQUEST");
        return value;
    }

    private OrderQuery opsOrder(ActorContext actor, String store, String order) {
        return new OrderQuery(new StoreRef(actor.tenantNo(), code(store)), code(order));
    }

    private static PrivateEvidencePort.Upload upload(byte[] body, String contentType) {
        if (body == null || body.length == 0 || body.length > MAX_EVIDENCE_BYTES)
            throw new MallException("INVALID_REQUEST");
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        byte[] copy = body.clone();
        return new PrivateEvidencePort.Upload(() -> new ByteArrayInputStream(copy), copy.length, type);
    }

    // ---------------------------------------------------------------- merchant (B end)

    public record QuoteRequest(List<QuoteLine> lines, String currency) {}

    @PostMapping(BIZ + "/quotes")
    public Object quote(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, QuoteRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(offline.quote(c.actor(), new QuoteCommand(c.store(), d.lines(), d.currency())));
    }

    public record EmptyRequest() {}

    @PostMapping(BIZ + "/orders/{orderNo}/instructions")
    public Object issue(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        json.read(body, EmptyRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(offline.issue(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    @GetMapping(BIZ + "/orders/{orderNo}/instructions")
    public Object instructions(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(offline.instructions(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    @PostMapping(BIZ + "/orders/{orderNo}/evidence")
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

    @PostMapping(BIZ + "/orders/{orderNo}/evidence/content")
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

    @GetMapping(BIZ + "/orders/{orderNo}/evidence/url")
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

    @PostMapping(BIZ + "/orders/{orderNo}/claims")
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

    @GetMapping(BIZ + "/orders/{orderNo}/claims")
    public Object claims(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(offline.claims(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    // ---------------------------------------------------------------- operations

    @GetMapping(OPS + "/profile")
    public Object profile(@RequestParam("currency") String currency) {
        var actor = context.operator("commerce:collection:profile:read");
        return ok(offline.profile(actor, currency).orElse(null));
    }

    @PutMapping(OPS + "/profile")
    public Object configure(@RequestBody String body) {
        var d = json.read(body, ProfileCommand.class);
        var actor = context.operator("commerce:collection:profile:write");
        return ok(offline.configureProfile(actor, d));
    }

    @GetMapping(OPS + "/claims")
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

    @PostMapping(OPS + "/quotes")
    public Object assistedQuote(@RequestParam("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, QuoteRequest.class);
        var actor = context.operator("commerce:mall:assisted:purchase");
        return ok(
                offline.quote(
                        actor,
                        new QuoteCommand(
                                new StoreRef(actor.tenantNo(), code(store)), d.lines(), d.currency())));
    }

    public record PlaceRequest(
            String quoteNo, String idempotencyKey, ShippingAddress shippingAddress) {}

    @PostMapping(OPS + "/orders")
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

    @PostMapping(OPS + "/orders/{orderNo}/instructions")
    public Object assistedIssue(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        json.read(body, EmptyRequest.class);
        var actor = context.operator("commerce:mall:assisted:purchase");
        return ok(offline.issue(actor, opsOrder(actor, store, order)));
    }

    @GetMapping(OPS + "/orders/{orderNo}/instructions")
    public Object opsInstructions(
            @PathVariable("orderNo") String order, @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(offline.instructions(actor, opsOrder(actor, store, order)));
    }

    @GetMapping(OPS + "/orders/{orderNo}/claims")
    public Object opsClaims(
            @PathVariable("orderNo") String order, @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(offline.claims(actor, opsOrder(actor, store, order)));
    }

    public record EvidenceRequest(String purpose) {}

    @PostMapping(OPS + "/orders/{orderNo}/evidence")
    public Object opsReserve(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, EvidenceRequest.class);
        var actor = context.operator("commerce:mall:offline:evidence");
        return ok(offline.reserveEvidence(actor, opsOrder(actor, store, order), d.purpose()));
    }

    @PostMapping(OPS + "/orders/{orderNo}/evidence/content")
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

    @GetMapping(OPS + "/orders/{orderNo}/evidence/url")
    public Object opsRead(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestParam("assetKey") String key) {
        var actor = context.operator("commerce:mall:offline:evidence");
        return ok(Map.of("url", offline.evidence(actor, opsOrder(actor, store, order), assetKey(key))));
    }

    public record DeclineRequest(String reason) {}

    @PostMapping(OPS + "/orders/{orderNo}/claims/{claimNo}/decline")
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
            String reason) {}

    @PostMapping(OPS + "/orders/{orderNo}/confirmations")
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
                                d.reason())));
    }

    public record VoidRequest(
            String actionNo,
            String reconciliationReference,
            String reason,
            String evidenceKey,
            Map<String, String> unmatchedDispositions) {}

    @PostMapping(OPS + "/orders/{orderNo}/voids")
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

    @PostMapping(OPS + "/orders/{orderNo}/refunds/{refundNo}/payouts")
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
