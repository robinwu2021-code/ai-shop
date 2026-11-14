package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/biz/stores/{storeNo}/mall")
public final class CommerceBuyerMallHttpController extends MallHttpSupport {
    private final MallFacade mall;
    private final MallQueryFacade queries;
    private final PaymentFacade payments;
    private final RefundFacade refunds;

    public CommerceBuyerMallHttpController(
            MallFacade mall,
            MallQueryFacade queries,
            PaymentFacade payments,
            RefundFacade refunds,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.mall = mall;
        this.queries = queries;
        this.payments = payments;
        this.refunds = refunds;
    }

    public record QuoteRequest(List<QuoteLine> lines, String currency) {}

    @PostMapping("/quotes")
    public Object quote(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, QuoteRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(mall.quote(c.actor(), new QuoteCommand(c.store(), d.lines(), d.currency())));
    }

    public record UpgradeRequest(
            String targetPlanVersionNo,
            String operationNo,
            long expectedEntitlementVersion,
            String targetSkuVersionNo) {}

    @PostMapping("/upgrade-quotes")
    public Object upgrade(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, UpgradeRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(
                mall.quoteUpgrade(
                        c.actor(),
                        new UpgradeCommand(
                                c.store(),
                                d.targetPlanVersionNo(),
                                d.operationNo(),
                                d.expectedEntitlementVersion(),
                                d.targetSkuVersionNo())));
    }

    public record PlaceRequest(
            String quoteNo, String idempotencyKey, ShippingAddress shippingAddress) {}

    @PostMapping("/orders")
    public Object place(@PathVariable("storeNo") String store, @RequestBody String body) {
        var d = json.read(body, PlaceRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(
                mall.placeOrder(
                        c.actor(),
                        new PlaceOrderCommand(
                                d.quoteNo(),
                                d.idempotencyKey(),
                                Optional.ofNullable(d.shippingAddress()),
                                c.store())));
    }

    @GetMapping("/orders")
    public Object orders(
            @PathVariable("storeNo") String store,
            @RequestParam(value = "afterOrderNo", required = false) String after,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(queries.orders(c.actor(), c.store(), after, limit));
    }

    @GetMapping("/orders/{orderNo}")
    public Object order(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(queries.order(c.actor(), new OrderQuery(c.store(), code(order))));
    }

    @GetMapping("/orders/{orderNo}/payments")
    public Object paymentStatus(
            @PathVariable("storeNo") String store, @PathVariable("orderNo") String order) {
        var c = context.buyer(store, "commerce:mall:read");
        return ok(
                queries.order(c.actor(), new OrderQuery(c.store(), code(order))).paymentAttempts());
    }

    public record RefreshRequest() {}

    @PostMapping("/orders/{orderNo}/payments/refresh")
    public Object refresh(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        json.read(body, RefreshRequest.class);
        var c = context.buyer(store, "commerce:mall:read");
        var query = new OrderQuery(c.store(), code(order));
        queries.order(c.actor(), query);
        if (payments == null) throw new MallException("PAYMENT_UNAVAILABLE");
        payments.refresh(new FulfillmentQuery(c.store().tenantNo(), query.orderNo()));
        return ok(queries.order(c.actor(), query));
    }

    public record CancelRequest(String operationNo, String reason) {}

    @PostMapping("/orders/{orderNo}/cancel")
    public Object cancel(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        var d = json.read(body, CancelRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        return ok(
                mall.cancelOrder(
                        c.actor(),
                        new CancelOrderCommand(
                                c.store(), code(order), d.operationNo(), d.reason())));
    }

    public record PaymentRequest(String channel, String payMethod) {}

    @PostMapping("/orders/{orderNo}/payments")
    public Object pay(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        var d = json.read(body, PaymentRequest.class);
        var c = context.buyer(store, "commerce:mall:purchase");
        if (payments == null) throw new MallException("PAYMENT_UNAVAILABLE");
        return ok(
                payments.startPayment(
                        c.actor(),
                        new StartPaymentCommand(
                                c.store(),
                                code(order),
                                d.channel(),
                                d.payMethod(),
                                context.payerReference(d.channel(), d.payMethod()))));
    }

    public record RefundPreviewRequest(String refundNo, List<String> sourcePeriodNos) {}

    @PostMapping("/orders/{orderNo}/refund-previews")
    public Object refundPreview(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        var d = json.read(body, RefundPreviewRequest.class);
        var c = context.buyer(store, "commerce:mall:refund:request");
        if (refunds == null) throw new MallException("REFUND_UNAVAILABLE");
        return ok(
                refunds.preview(
                        c.actor(),
                        new RefundPreviewCommand(
                                c.store(),
                                code(order),
                                d.refundNo(),
                                d.sourcePeriodNos() == null ? List.of() : d.sourcePeriodNos())));
    }

    public record RefundRequest(
            String refundNo, Money cashAmount, String reason, List<String> sourcePeriodNos) {}

    @PostMapping("/orders/{orderNo}/refunds")
    public Object refund(
            @PathVariable("storeNo") String store,
            @PathVariable("orderNo") String order,
            @RequestBody String body) {
        var d = json.read(body, RefundRequest.class);
        var c = context.buyer(store, "commerce:mall:refund:request");
        if (refunds == null) throw new MallException("REFUND_UNAVAILABLE");
        return ok(
                refunds.request(
                        c.actor(),
                        new RefundCommand(
                                c.store(),
                                code(order),
                                d.refundNo(),
                                d.cashAmount(),
                                d.reason(),
                                d.sourcePeriodNos())));
    }
}
