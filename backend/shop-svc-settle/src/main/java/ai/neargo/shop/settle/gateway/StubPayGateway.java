package ai.neargo.shop.settle.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发期的假网关：记日志、恒成功。
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
}
