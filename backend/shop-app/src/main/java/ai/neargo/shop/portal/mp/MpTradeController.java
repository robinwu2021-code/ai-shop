package ai.neargo.shop.portal.mp;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.dto.CartItemVO;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.CartService;
import ai.neargo.shop.trade.service.OrderService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车与交易端点（[API 清单 §2.4]）。全部需要登录，由过滤器链保证。
 */
@RestController
@Validated
public class MpTradeController {

    private final CartService cartService;
    private final OrderService orderService;
    private final AfterSaleService afterSaleService;

    public MpTradeController(CartService cartService, OrderService orderService,
                             AfterSaleService afterSaleService) {
        this.cartService = cartService;
        this.orderService = orderService;
        this.afterSaleService = afterSaleService;
    }

    // ---- 购物车

    @GetMapping("/mp/cart")
    public List<CartItemVO> cart() {
        return cartService.list();
    }

    @PostMapping("/mp/cart/add")
    public List<CartItemVO> cartAdd(@RequestBody CartAddReq req) {
        return cartService.add(req.goodsNo(), req.skuNo(), req.qty());
    }

    @PostMapping("/mp/cart/update")
    public List<CartItemVO> cartUpdate(@RequestBody CartUpdateReq req) {
        return cartService.update(req.skuNo(), req.qty());
    }

    @PostMapping("/mp/cart/remove")
    public List<CartItemVO> cartRemove(@RequestBody CartRemoveReq req) {
        return cartService.remove(req.skuNos());
    }

    // ---- 交易

    @PostMapping("/mp/order/preview")
    public OrderVO preview(@RequestBody CreateOrderReq req) {
        return orderService.preview(req.toCommand());
    }

    /**
     * 下单。{@code Idempotency-Key} 走请求头而不是请求体 ——
     * 它是传输层语义（重试同一个请求），放进业务体会诱使有人为了「重新下单」而换 key。
     */
    @PostMapping("/mp/order")
    public OrderVO create(@RequestBody CreateOrderReq req,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return orderService.create(req.toCommand(), idemKey == null ? req.idempotencyKey() : idemKey);
    }

    @PostMapping("/mp/order/{orderNo}/pay")
    public OrderService.PayResult pay(@PathVariable String orderNo) {
        return orderService.pay(orderNo);
    }

    /** 支付结果回查：**端侧不自判成功**，付款后轮询这个。 */
    @GetMapping("/mp/order/{orderNo}/pay-result")
    public OrderVO payResult(@PathVariable String orderNo) {
        return orderService.payResult(orderNo);
    }

    @GetMapping("/mp/order")
    public PageData<OrderVO> orderList(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return orderService.list(status, page, Math.min(size, 50));
    }

    @GetMapping("/mp/order/{orderNo}")
    public OrderVO orderDetail(@PathVariable String orderNo) {
        return orderService.detail(orderNo);
    }

    @PostMapping("/mp/order/{orderNo}/confirm-receipt")
    public OrderVO confirmReceipt(@PathVariable String orderNo) {
        return orderService.confirmReceipt(orderNo);
    }

    @PostMapping("/mp/order/{orderNo}/cancel")
    public OrderVO cancel(@PathVariable String orderNo, @RequestBody(required = false) CancelReq req) {
        return orderService.cancel(orderNo, req == null ? null : req.reason());
    }

    // ---- 售后

    /** 售后是**子单粒度**：路径上的 orderNo 就是子单号（Q6）。 */
    @PostMapping("/mp/order/{orderNo}/after-sale")
    public AfterSaleVO applyAfterSale(@PathVariable String orderNo, @RequestBody ApplyAfterSaleReq req) {
        return afterSaleService.apply(orderNo, new AfterSaleService.ApplyCommand(
                req.type(), req.reason(), req.images(), req.refundMinor()));
    }

    @GetMapping("/mp/after-sale/reasons")
    public List<String> afterSaleReasons() {
        return afterSaleService.reasons();
    }

    @GetMapping("/mp/after-sale")
    public List<AfterSaleVO> myAfterSales() {
        return afterSaleService.myList();
    }

    @GetMapping("/mp/after-sale/{afterSaleNo}")
    public AfterSaleVO afterSaleDetail(@PathVariable String afterSaleNo) {
        return afterSaleService.detail(afterSaleNo);
    }

    @PostMapping("/mp/after-sale/{afterSaleNo}/cancel")
    public AfterSaleVO cancelAfterSale(@PathVariable String afterSaleNo) {
        return afterSaleService.cancel(afterSaleNo);
    }

    @PostMapping("/mp/after-sale/{afterSaleNo}/ship")
    public AfterSaleVO shipBack(@PathVariable String afterSaleNo, @RequestBody ShipBackReq req) {
        return afterSaleService.shipBack(afterSaleNo, req.expressCompany(), req.expressNo());
    }

    @PostMapping("/mp/after-sale/{afterSaleNo}/escalate")
    public AfterSaleVO escalate(@PathVariable String afterSaleNo, @RequestBody EscalateReq req) {
        return afterSaleService.escalate(afterSaleNo, req.appeal());
    }

    public record ApplyAfterSaleReq(@NotBlank String type, @NotBlank String reason,
                                    List<String> images, Long refundMinor) {
    }

    public record ShipBackReq(String expressCompany, @NotBlank String expressNo) {
    }

    public record EscalateReq(String appeal) {
    }

    public record CartAddReq(@NotBlank String goodsNo, @NotBlank String skuNo, int qty) {
    }

    public record CartUpdateReq(@NotBlank String skuNo, int qty) {
    }

    public record CartRemoveReq(List<String> skuNos) {
    }

    public record CancelReq(String reason) {
    }

    public record CreateOrderReq(List<Item> items, String fulfillment, String pickupNo, String addressId,
                                 String couponNo, String remark, String idempotencyKey) {

        public record Item(String goodsNo, String skuNo, int qty) {
        }

        OrderService.CreateOrderCommand toCommand() {
            return new OrderService.CreateOrderCommand(
                    items == null ? List.of() : items.stream()
                            .map(i -> new OrderService.CreateOrderCommand.Item(i.goodsNo(), i.skuNo(), i.qty()))
                            .toList(),
                    fulfillment, pickupNo, addressId, couponNo, remark);
        }
    }
}
