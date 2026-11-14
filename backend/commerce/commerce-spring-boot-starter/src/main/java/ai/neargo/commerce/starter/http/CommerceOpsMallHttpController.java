package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ops/biz-mall/orders")
public final class CommerceOpsMallHttpController extends MallHttpSupport {
    private final MallQueryFacade queries;
    private final RefundFacade refunds;
    private final ai.neargo.commerce.api.fulfillment.FulfillmentFacade fulfillment;

    public CommerceOpsMallHttpController(
            MallQueryFacade queries,
            RefundFacade refunds,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        this(queries, refunds, null, context, envelope);
    }

    public CommerceOpsMallHttpController(
            MallQueryFacade queries,
            RefundFacade refunds,
            ai.neargo.commerce.api.fulfillment.FulfillmentFacade fulfillment,
            MallHttpContextPort context,
            MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.queries = queries;
        this.refunds = refunds;
        this.fulfillment = fulfillment;
    }

    @GetMapping
    public Object orders(
            @RequestParam("storeNo") String store,
            @RequestParam(value = "afterOrderNo", required = false) String after,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(queries.orders(actor, new StoreRef(actor.tenantNo(), code(store)), after, limit));
    }

    @GetMapping("/{orderNo}")
    public Object order(
            @PathVariable("orderNo") String order, @RequestParam("storeNo") String store) {
        var actor = context.operator("commerce:mall:order:read");
        return ok(
                queries.order(
                        actor,
                        new OrderQuery(new StoreRef(actor.tenantNo(), code(store)), code(order))));
    }

    public record ShipmentRequest(
            String orderComponentNo,
            String operationNo,
            String carrierCode,
            String trackingNo,
            long quantity) {}

    @PostMapping("/{orderNo}/shipments")
    public Object ship(
            @PathVariable("orderNo") String order,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, ShipmentRequest.class);
        var actor = context.operator("commerce:mall:shipment:write");
        var query = new OrderQuery(new StoreRef(actor.tenantNo(), code(store)), code(order));
        queries.order(actor, query);
        if (fulfillment == null) throw new MallException("FULFILLMENT_UNAVAILABLE");
        fulfillment.confirmShipment(
                new ai.neargo.commerce.api.fulfillment.HardwareShipmentCommand(
                        actor.tenantNo(),
                        code(order),
                        code(d.orderComponentNo()),
                        d.operationNo(),
                        d.carrierCode(),
                        d.trackingNo(),
                        d.quantity(),
                        actor.principalNo()));
        return ok(queries.order(actor, query));
    }

    public record ReviewRequest(String requestDigest, String reviewQuoteNo) {}

    @PostMapping("/{orderNo}/refunds/{refundNo}/review-quotes")
    public Object review(
            @PathVariable("orderNo") String order,
            @PathVariable("refundNo") String refund,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, ReviewRequest.class);
        var actor = context.operator("commerce:refund:review");
        available();
        return ok(
                refunds.reviewQuote(
                        actor,
                        new RefundReviewQuoteCommand(
                                new StoreRef(actor.tenantNo(), code(store)),
                                code(order),
                                code(refund),
                                d.requestDigest(),
                                d.reviewQuoteNo())));
    }

    public record ApproveRequest(
            String approvalDigest,
            String reviewQuoteNo,
            Money cashAmount,
            Money restoreCredit,
            String reviewEvidence) {}

    @PostMapping("/{orderNo}/refunds/{refundNo}/approve")
    public Object approve(
            @PathVariable("orderNo") String order,
            @PathVariable("refundNo") String refund,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, ApproveRequest.class);
        var actor = context.operator("commerce:refund:review");
        available();
        return ok(
                refunds.approve(
                        actor,
                        new RefundApprovalCommand(
                                new StoreRef(actor.tenantNo(), code(store)),
                                code(order),
                                code(refund),
                                d.approvalDigest(),
                                d.cashAmount(),
                                d.restoreCredit(),
                                d.reviewEvidence(),
                                d.reviewQuoteNo())));
    }

    public record RejectRequest(String requestDigest, String reviewEvidence) {}

    @PostMapping("/{orderNo}/refunds/{refundNo}/reject")
    public Object reject(
            @PathVariable("orderNo") String order,
            @PathVariable("refundNo") String refund,
            @RequestParam("storeNo") String store,
            @RequestBody String body) {
        var d = json.read(body, RejectRequest.class);
        var actor = context.operator("commerce:refund:review");
        available();
        return ok(
                refunds.reject(
                        actor,
                        new RefundRejectionCommand(
                                new StoreRef(actor.tenantNo(), code(store)),
                                code(order),
                                code(refund),
                                d.requestDigest(),
                                d.reviewEvidence())));
    }

    private void available() {
        if (refunds == null) throw new MallException("REFUND_UNAVAILABLE");
    }
}
