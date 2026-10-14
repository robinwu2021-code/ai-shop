package ai.neargo.shop.trade.order.service.create;

/**
 * 下单落库出口（小 port）。把 insert 与幂等查询从 MyBatis 抽象出来，让落库编排可纯逻辑单测。
 * 接线阶段提供基于 {@code TradeMappers} 的实现（负责 insert 支付单/订单/行、idem 记录、数据域）。
 */
public interface OrderWriteStore {

    /** 幂等：该用户用这个 idemKey 是否已下过单；返回已存在的支付单号，没有返回 null。 */
    String findPayNoByIdemKey(String userNo, String idemKey);

    /** 落库一次下单：支付单 + 各商家订单 + 商品行，并记 idemKey。 */
    void savePayment(PaymentDraft draft, String idemKey);
}
