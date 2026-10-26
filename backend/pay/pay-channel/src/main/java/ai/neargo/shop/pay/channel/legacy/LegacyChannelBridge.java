package ai.neargo.shop.pay.channel.legacy;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;

/**
 * 旧调用面 → 新管线（TDD-支付服务-I2渠道统一封装 S4）。下单、退款、查单三个调用点签名不变，
 * 换成走 {@link ChannelGateway}：调用记录、日志、熔断、隔离、超时从此对它们生效；结果再译回旧类型，
 * 调用方的判断一行不改 —— 改判断是 I3 订单与流水拆分的事，混在一起就分不清哪一步改坏了什么。
 *
 * <p>零金额（免单）不进管线：渠道永远看不到它，参数校验也会拒绝 0 元。
 */
public final class LegacyChannelBridge {

    private final ChannelGateway channels;
    private final PayGatewayRouter router;
    private final ChannelContextFactory contexts;

    public LegacyChannelBridge(ChannelGateway channels, PayGatewayRouter router, ChannelContextFactory contexts) {
        this.channels = channels;
        this.router = router;
        this.contexts = contexts;
    }

    public PayGateway.PrepayResult prepay(String payChannel, PayGateway.PrepayCommand c) {
        PayGateway gateway = router.of(payChannel);
        if (c.amountMinor() <= 0) {
            return gateway.prepay(c);
        }
        ChannelResult r = channels.execute(context(gateway, c.currency(), c.subMchId()),
                new PayCommand(meta("PAY", c.outTradeNo()), PayScene.ONLINE_SDK, new Money(c.amountMinor(), c.currency()),
                        c.subject(), c.outTradeNo(), null, c.payerId(), null));
        return switch (r) {
            case ChannelResult.Pending p -> PayGateway.PrepayResult.ok(p.instruction().asParams(), p.channelTradeNo());
            case ChannelResult.Succeeded s -> PayGateway.PrepayResult.ok(s.extra(), s.channelTradeNo());
            case ChannelResult.Failed f -> PayGateway.PrepayResult.fail(f.channelMessage() != null ? f.channelMessage()
                    : f.code().name());
            // 原语义：没有回执就抛，订单留在待支付，由查单收口
            case ChannelResult.Unknown u -> throw new IllegalStateException("PAY_CHANNEL_" + u.reason());
        };
    }

    public PayGateway.Result refund(String payChannel, PayGateway.TxContext ctx, long amountMinor, String outRefundNo,
                                    String reason) {
        PayGateway gateway = router.of(payChannel);
        if (amountMinor <= 0) {
            return gateway.refund(ctx, amountMinor, outRefundNo, reason);
        }
        ChannelResult r = channels.execute(context(gateway, ctx.currency(), ctx.subMchId()),
                new RefundCommand(meta("REFUND", outRefundNo), ctx.outTradeNo(), ctx.tradeNo(),
                        new Money(ctx.totalMinor(), ctx.currency()), new Money(amountMinor, ctx.currency()),
                        outRefundNo, reason));
        return switch (r) {
            case ChannelResult.Pending p -> PayGateway.Result.ok(p.channelTradeNo());
            case ChannelResult.Succeeded s -> PayGateway.Result.ok(s.channelTradeNo());
            // 管线里的明确失败：要么渠道明确拒绝，要么根本没发出去（校验、熔断、停用）—— 两者都可据以判失败
            case ChannelResult.Failed f -> PayGateway.Result.rejected(f.code().name());
            case ChannelResult.Unknown u -> "REFUND_SEND_UNPROVEN".equals(u.reason())
                    ? PayGateway.Result.fatal(u.reason()) : PayGateway.Result.retry(u.reason());
        };
    }

    public PayGateway.QueryResult query(String payChannel, String outTradeNo) {
        PayGateway gateway = router.of(payChannel);
        ChannelResult r = channels.execute(context(gateway, null, null),
                new QueryCommand(meta("QUERY", outTradeNo), outTradeNo));
        return switch (r) {
            case ChannelResult.Succeeded s -> s.channelAmount() == null
                    ? new PayGateway.QueryResult(true, true, true, 0, s.channelTradeNo())
                    : PayGateway.QueryResult.paid(s.channelAmount().minor(), s.channelTradeNo(), s.channelAmount().currency());
            case ChannelResult.Pending p -> new PayGateway.QueryResult(true, false, true, 0, p.channelTradeNo());
            case ChannelResult.Failed f when f.code() == PayErrorCode.TRADE_NOT_FOUND -> PayGateway.QueryResult.notFound();
            // 查不通、熔断、停用：都不是「没有这笔」，不可据以关单
            case ChannelResult.Failed f -> PayGateway.QueryResult.failed();
            case ChannelResult.Unknown u -> PayGateway.QueryResult.failed();
        };
    }

    private ChannelAccountContext context(PayGateway gateway, String currency, String subMchId) {
        // 地区与支付商户在 I4（pay_merchant）之前还没有：null = 不限地区，凭据仍由原网关按子商户号取
        return contexts.from(ChannelRoute.of(gateway.payChannel(), currency, subMchId)
                .withAccessMode(LegacyAccessModes.of(gateway)));
    }

    private static InvocationMeta meta(String bizType, String bizNo) {
        return new InvocationMeta(null, null, null, null, null, SourceInfo.system(bizType, bizNo));
    }
}
