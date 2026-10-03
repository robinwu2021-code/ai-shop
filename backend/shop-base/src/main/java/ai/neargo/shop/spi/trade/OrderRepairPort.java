package ai.neargo.shop.spi.trade;

/**
 * settle → trade：对账自查发现掉单后，把订单**推回正轨**。
 *
 * <p><b>为什么是 Port 而不是让对账自己改状态</b>：支付成功要做的不止改一个字段 ——
 * 扣库存、生成结算单、发积分、发通知、建履约任务，全都挂在
 * {@code OrderService.markPaid} 上。对账里自己写一段「把 status 改成 SUCCESS」，
 * 漏掉的那几件事不会报错，要等用户问「我付了钱怎么没有积分」才知道。
 *
 * <p>所以这里只暴露两个**动作**，且都是既有链路的入口，不是新写的：
 * 一个把单推成已支付，一个把确认没付的单关掉。
 */
public interface OrderRepairPort {

    /**
     * 补一次支付成功（等同于回调到达）。
     *
     * <p><b>必须幂等</b>：对账每轮都可能再查到同一笔，而回调也可能迟到 ——
     * 两条路径撞在一起时，重复入账是资金事故。幂等由 {@code markPaid} 保证。
     *
     * @param orderNo    订单号
     * @param payChannel 通道码
     * @param tradeNo    通道流水号，落进订单用于日后对账
     */
    void markPaid(String orderNo, String payChannel, String tradeNo);

    /**
     * 关掉一笔确认没付的单并释放库存。
     *
     * <p><b>只在通道明确回「没有这笔」时调用</b>。查询失败时调用它，
     * 会把一笔已付的单关掉 —— 钱在通道那边，而订单已关闭。
     */
    void closeUnpaid(String orderNo);
}
