package ai.neargo.shop.spi.pay;

/**
 * settle → channel：拿我方流水号问通道「这笔到底付了没有」。
 *
 * <p><b>为什么要经过这一层</b>：微信与支付宝的查单报文格式，与「这笔钱怎么对账」无关。
 * settle 直接 import 具体网关的话，换通道要改对账代码，
 * 而 ops 部署（不含支付通道）会连编译都过不去 —— 架构守卫拦的正是这个。
 *
 * <p>只暴露<b>一个判断</b>：付了没有、多少钱。不返回通道原始报文 ——
 * 返回的话，对账逻辑迟早会去读某个只有微信有的字段，
 * 而那一行在支付宝上恒为 null，且不报错。
 */
public interface PayQueryPort {

    /**
     * @param payChannel  通道码（{@code stl_payment.pay_channel}）
     * @param outTradeNo  我方流水号
     * @return 通道侧的真实状态
     */
    Result query(String payChannel, String outTradeNo);

    /**
     * @param ok    查询本身是否成功。<b>false 时其余字段不可信</b>，
     *              而且<b>绝不能据此关单</b> —— 查询失败与「通道没有这笔」是两件事，
     *              混在一起会把已付的单关掉
     * @param paid  通道侧是否已支付
     * @param found 通道有没有这笔单
     */
    record Result(boolean ok, boolean paid, boolean found, long amountMinor, String tradeNo) {
    }
}
