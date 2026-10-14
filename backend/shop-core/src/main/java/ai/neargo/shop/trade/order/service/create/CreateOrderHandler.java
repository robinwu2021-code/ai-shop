package ai.neargo.shop.trade.order.service.create;

import java.util.List;

/**
 * 下单主干（订单服务 2.0）：已解析的购物行 → 拆单 → 建支付单/订单/行 → 落库（幂等+锁库存）。
 *
 * <p>本门面只负责"从 {@link CartLine} 到落库"这一段纯编排；<b>其上游两步尚未接入</b>：
 * ① 落店+定价解析（{@code CreateOrderCommand.Line} → {@code CartLine}，取商家/门店/单价）；
 * ② 优惠/积分/运费对金额的调整。两者依赖商品/营销/支付域能力，作为后续环节补入，
 * 届时 {@code OrderCommandServiceImpl.create} = 解析 + 金额调整 + 本 handler。
 */
public class CreateOrderHandler {

    private final OrderPersister persister;
    private final OrderNoGen noGen;

    public CreateOrderHandler(OrderPersister persister, OrderNoGen noGen) {
        this.persister = persister;
        this.noGen = noGen;
    }

    /** @return 支付单号（幂等命中时为原单号） */
    public String create(String userNo, String communityNo, List<CartLine> lines, String idemKey) {
        List<OrderGroup> groups = OrderSplitter.splitByStore(lines);
        String payNo = noGen.nextPayNo();
        PaymentDraft draft = PaymentDraftFactory.build(
                payNo, userNo, communityNo, groups, n -> noGen.subOrderNo(payNo, n));
        return persister.persist(draft, userNo, idemKey);
    }
}
