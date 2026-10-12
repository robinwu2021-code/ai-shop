package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;

import java.util.List;

/**
 * 下单落库编排：<b>幂等 → 锁库存 → 落库</b>（订单服务 2.0）。
 *
 * <p>幂等靠 {@code idemKey}：命中直接返回原支付单，不重复锁库存、不重复落库。
 * 锁库存经 {@link StockPort#lock}（原子、按门店，守卫 available-qty≥0）；有缺货整单失败。
 * 库存需求从草稿的行提取（skuNo+qty+供货门店）。
 */
public class OrderPersister {

    private final OrderWriteStore store;
    private final StockPort stock;

    public OrderPersister(OrderWriteStore store, StockPort stock) {
        this.store = store;
        this.stock = stock;
    }

    public String persist(PaymentDraft draft, String userNo, String idemKey) {
        String existing = store.findPayNoByIdemKey(userNo, idemKey);
        if (existing != null) {
            return existing;
        }
        String payNo = draft.payment().getOrderNo();
        List<String> failed = stock.lock(payNo, stockNeeds(draft));
        if (failed != null && !failed.isEmpty()) {
            throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, String.join(",", failed));
        }
        store.savePayment(draft, idemKey);
        return payNo;
    }

    private static List<StockPort.SkuQty> stockNeeds(PaymentDraft draft) {
        return draft.orders().stream()
                .flatMap(o -> o.items().stream())
                .map(i -> new StockPort.SkuQty(i.getSkuNo(), i.getQty(), i.getSupplyStoreNo()))
                .toList();
    }
}
