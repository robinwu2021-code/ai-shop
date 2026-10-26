package ai.neargo.shop.pay.channel.legacy;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 过渡适配（Adapter）：把一个现有 {@link PayGateway} 挂到新管线下（TDD-支付服务-I2渠道统一封装 S4）。
 *
 * <p>开发计划的风险应对是「处理器拆分逐个替换，旧网关保留为兼容组合直到全部切换」—— 这就是那个兼容组合：
 * 管线（记录、日志、熔断、隔离、超时、分类）先对现有微信 / 支付宝 / 测试渠道全量生效，
 * 渠道报文的组装仍在原网关里，逐个拆成独立处理器时换掉这一层，管线不动。
 *
 * <p>结果映射按原调用方的语义，一字不改：下单被拒 → 明确失败；下单没有回执 → 不明；
 * 查不通 → 不明（不能当成「没有这笔」去关单）；退款受理 → 处理中；退款「可重试」或「没被明确拒绝」→ 不明（只查不重发）。
 */
public final class LegacyGatewayHandler implements ChannelHandler {

    private final PayGateway gateway;
    private final AccessMode accessMode;

    public LegacyGatewayHandler(PayGateway gateway, AccessMode accessMode) {
        this.gateway = gateway;
        this.accessMode = accessMode;
    }

    @Override
    public Set<ChannelKey.Pattern> supports() {
        String ch = gateway.payChannel();
        return Set.of(new ChannelKey.Pattern(null, ch, accessMode, PayScene.ANY, ChannelOperation.PAY),
                new ChannelKey.Pattern(null, ch, accessMode, PayScene.ANY, ChannelOperation.QUERY),
                new ChannelKey.Pattern(null, ch, accessMode, PayScene.ANY, ChannelOperation.REFUND));
    }

    @Override
    public ChannelResult handle(Invocation inv, ChannelCommand command) {
        ChannelAccountContext account = inv.account();
        return switch (command) {
            case PayCommand c -> pay(inv, account, c);
            case QueryCommand c -> query(inv, account, c);
            case RefundCommand c -> refund(inv, account, c);
            default -> new ChannelResult.Failed(PayErrorCode.CHANNEL_UNSUPPORTED, null,
                    "过渡适配不支持 " + command.operation(), false);
        };
    }

    private ChannelResult pay(Invocation inv, ChannelAccountContext account, PayCommand c) {
        inv.markSent();
        PayGateway.PrepayResult r = gateway.prepay(new PayGateway.PrepayCommand(c.outTradeNo(), c.amount().minor(),
                c.amount().currency(), c.subject(), "JSAPI", account.subMerchantId(), c.payerId()));
        if (r == null) {
            throw new ChannelTransportException(ChannelTransportException.Kind.UNPARSEABLE, "下单没有回执");
        }
        if (!r.success()) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null, r.message(), false);
        }
        return new ChannelResult.Pending(new ai.neargo.shop.spi.pay.PayInstruction.AppSdkParams(r.params()), r.tradeNo());
    }

    private ChannelResult query(Invocation inv, ChannelAccountContext account, QueryCommand c) {
        inv.markSent();
        PayGateway.QueryResult r = gateway.query(c.outTradeNo());
        if (r == null || !r.ok()) {
            return new ChannelResult.Unknown("QUERY_FAILED");
        }
        if (!r.found()) {
            return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "渠道没有这笔", false);
        }
        if (!r.paid()) {
            return new ChannelResult.Pending(ai.neargo.shop.spi.pay.PayInstruction.none(), r.tradeNo());
        }
        String currency = r.currency() != null ? r.currency() : account.currency();
        Money amount = currency == null ? null : new Money(r.amountMinor(), currency);
        return new ChannelResult.Succeeded(r.tradeNo(), amount, null, Map.of());
    }

    private ChannelResult refund(Invocation inv, ChannelAccountContext account, RefundCommand c) {
        inv.markSent();
        PayGateway.Result r = gateway.refund(new PayGateway.TxContext(account.subMerchantId(), c.originChannelTradeNo(),
                        c.originOutTradeNo(), c.originAmount().minor(), c.refundAmount().currency()),
                c.refundAmount().minor(), c.outRefundNo(), c.reason());
        if (r == null || (!r.success() && r.retryable())) {
            return new ChannelResult.Unknown("REFUND_SEND_UNKNOWN");
        }
        if (!r.success()) {
            return r.definitiveRejection()
                    ? new ChannelResult.Failed(PayErrorCode.REFUND_REJECTED, null, r.message(), false)
                    : new ChannelResult.Unknown("REFUND_SEND_UNPROVEN");
        }
        return new ChannelResult.Pending(ai.neargo.shop.spi.pay.PayInstruction.none(), r.providerNo());
    }

    public String payChannel() {
        return gateway.payChannel();
    }

    public AccessMode accessMode() {
        return accessMode;
    }
}
