package ai.neargo.shop.pay.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发期的假网关：记日志、恒成功。
 *
 * <p><b>它刻意不继承 {@code AbstractPayGateway}。</b>那个基类的骨架是
 * 「能力位 → 构造报文 → HTTP → 解析回执」，而这个假网关<b>根本不发 HTTP</b>，
 * 硬套进去要先造一个假的 ChannelClient —— 那时它测的就不再是「链路通不通」，
 * 是「我造的假客户端返回了什么」。真通道的共性归基类，假网关是另一件东西。
 *
 * <p><b>只在 {@code shop.pay.stub=true} 时装配</b>，默认不开 ——
 * 默认装配的话，真实现没接上时线上会「支付成功」而钱根本没动，
 * 而这个失败<b>没有任何症状</b>，要到对账日才发现。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.stub", havingValue = "true")
public class StubPayGateway implements PayGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPayGateway.class);

    /** 假网关同时冒充两个通道 —— 开发期不区分 */
    @Override
    public String payChannel() {
        return "STUB";
    }

    /**
     * 下单：编一组与微信 JSAPI 同形的参数。**恒成功** —— 它是开发期的假网关。
     *
     * <p>与 {@code TEST} 通道的区别在这里最明显：这个不记状态、不校验金额，
     * 因而<b>证明不了链路通</b>。联调要用 TEST，不要用它。
     */
    @Override
    public PrepayResult prepay(PrepayCommand cmd) {
        log.info("[stub] 下单 outTradeNo={} amount={}", cmd.outTradeNo(), cmd.amountMinor());
        return PrepayResult.ok(java.util.Map.of(
                "prepayId", "stub_" + cmd.outTradeNo(),
                "outTradeNo", cmd.outTradeNo(),
                "amount", String.valueOf(cmd.amountMinor())), "STUBTX-" + cmd.outTradeNo());
    }

    @Override
    public Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        log.info("[stub] 补差 sub_mchid={} tx={} amount={} req={}",
                ctx.subMchId(), ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("STUB-SUBSIDY-" + requestNo);
    }

    @Override
    public Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        log.info("[stub] 补差回退 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("STUB-SUBSIDY-RET-" + requestNo);
    }

    @Override
    public Result split(TxContext ctx, long amountMinor, String requestNo) {
        log.info("[stub] 分账 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("STUB-SPLIT-" + requestNo);
    }

    @Override
    public Result splitReverse(TxContext ctx, long amountMinor, String requestNo) {
        log.info("[stub] 分账回退 tx={} amount={} req={}", ctx.tradeNo(), amountMinor, requestNo);
        return Result.ok("STUB-SPLIT-REV-" + requestNo);
    }

    @Override
    public Result refund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        log.info("[stub] 退款 tx={} amount={} req={} reason={}",
                ctx.tradeNo(), amountMinor, requestNo, reason);
        return Result.ok("STUB-REFUND-" + requestNo);
    }

    /**
     * 假查单：<b>恒定回「通道没有这笔」</b>，不是「已支付」。
     *
     * <p>回「已支付」的话，开发库里每一笔停在 PENDING 的单都会被自动补成已付 ——
     * 那会让对账自查看起来一直在工作，而它其实什么都没验证。
     * 回 notFound 则会走关单分支，与真实的「发起失败」一致。
     */
    @Override
    public QueryResult query(String outTradeNo) {
        log.info("[stub] 查单 out_trade_no={} → 通道无此单", outTradeNo);
        return QueryResult.notFound();
    }
}
