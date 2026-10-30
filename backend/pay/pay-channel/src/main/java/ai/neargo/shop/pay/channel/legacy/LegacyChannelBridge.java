package ai.neargo.shop.pay.channel.legacy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.capability.TransactionCapability;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.platform.InProcessChannel;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 旧调用面 → 新渠道层（TDD-支付服务-I2渠道统一封装 S4；W2 S5 去掉路由表）。下单、退款、查单、查退款的调用方签名不变：
 * 命令进管线（或直达处理器），四态结果再译回旧类型 —— 调用方的判断一行不改，改判断是 I3 支付单 / 退款单拆分的事。
 *
 * <p>哪些走管线、哪些直达处理器，与 I2 时<b>逐项相同</b>：
 * <ul>
 *   <li>下单、退款、查单 → 管线（调用记录、日志、熔断、隔离、超时、分类）；</li>
 *   <li>查退款（三态与五态）、零金额（免单）→ 直达处理器：五态与四态不是一一对应（I2 偏差 3），零元过不了校验。</li>
 * </ul>
 * 没装的渠道与旧路由表一样直接失败（{@code INTERNAL_ERROR}），不回落到任何别的渠道 —— 回落等于把钱发到另一个渠道的商户号。
 */
public final class LegacyChannelBridge {

    private static final Logger log = LoggerFactory.getLogger(LegacyChannelBridge.class);
    private static final String UNPROVEN = "REFUND_SEND_UNPROVEN";

    private final ChannelGateway channels;
    private final ChannelHandlerRegistry handlers;
    private final ChannelCapabilityRegistry capabilities;
    private final ChannelContextFactory contexts;

    public LegacyChannelBridge(ChannelGateway channels, ChannelHandlerRegistry handlers,
                               ChannelCapabilityRegistry capabilities, ChannelContextFactory contexts) {
        this.channels = channels;
        this.handlers = handlers;
        this.capabilities = capabilities;
        this.contexts = contexts;
    }

    /** 这个渠道在本部署装了没有（替代 {@code PayGatewayRouter#supports}） */
    public boolean supports(String payChannel) {
        return capabilities.supports(payChannel);
    }

    /** 能力实现顺带实现的其他接口（如积分资金动作 {@code PayFundsOperations}）；渠道没装或装了两种接入模式为空 */
    public <T> Optional<T> capability(String payChannel, Class<T> type) {
        return capabilities.find(payChannel, type);
    }

    /** 现有国内退款路线只认人民币，其他币种要实现显式放开（I3 偏差 2） */
    public boolean supportsRefundCurrency(String payChannel, String currency) {
        require(payChannel);
        return capabilities.find(payChannel, TransactionCapability.class)
                .map(t -> t.supportsRefundCurrency(currency)).orElse(false);
    }

    /**
     * 下单。渠道明确拒绝 → 失败（带原因）；<b>不明</b>（超时、回执缺件）→ 非明确的失败：调用方照旧回「下单失败」、原收款意图留待查单，
     * 管线同时已登记补查 —— 而不是像 I2 那样抛出去变成一个 500。
     */
    public PayGateway.PrepayResult prepay(String payChannel, PayGateway.PrepayCommand c) {
        ChannelAccountContext ctx = context(payChannel, c.currency(), c.subMchId());
        PayCommand cmd = new PayCommand(meta("PAY", c.outTradeNo()), PayScene.ONLINE_SDK,
                new Money(c.amountMinor(), c.currency()), c.subject(), c.outTradeNo(), null, c.payerId(), null);
        ChannelResult r = c.amountMinor() <= 0 ? direct(ctx, cmd) : channels.execute(ctx, cmd);
        return switch (r) {
            case ChannelResult.Pending p -> PayGateway.PrepayResult.ok(p.instruction().asParams(), p.channelTradeNo());
            case ChannelResult.Succeeded s -> PayGateway.PrepayResult.ok(s.extra(), s.channelTradeNo());
            // This marker comes only from handlers with explicit local unsent proof, never inferred from UNKNOWN.
            case ChannelResult.Failed f when "LOCAL_PAYMENT_NOT_SENT".equals(f.channelCode()) ->
                    PayGateway.PrepayResult.notSent(f.channelMessage());
            case ChannelResult.Failed f -> PayGateway.PrepayResult.fail(f.channelMessage() != null ? f.channelMessage()
                    : f.code().name());
            case ChannelResult.Unknown u -> PayGateway.PrepayResult.unknown("PAY_CHANNEL_" + u.reason());
        };
    }

    /**
     * 退款。受理 → 成功（带渠道退款单号）；渠道 / 管线明确拒绝 → 明确拒绝（I2 偏差 5：要么渠道拒了，要么根本没发出去）；
     * 渠道答了但说不清（2xx 带错误码、应答验签不过、未登记的错误码）→ 不可重试的不明（{@value #UNPROVEN}）；
     * 发出去没回音（超时、5xx、连不上）→ 可重试的不明。两种不明调用方都只查不重发。
     */
    public PayGateway.Result refund(String payChannel, PayGateway.TxContext tx, long amountMinor, String outRefundNo,
                                    String reason) {
        ChannelAccountContext ctx = context(payChannel, tx.currency(), tx.subMchId());
        RefundCommand cmd = new RefundCommand(meta("REFUND", outRefundNo), tx.outTradeNo(), tx.tradeNo(),
                new Money(tx.totalMinor(), tx.currency()), new Money(amountMinor, tx.currency()), outRefundNo, reason);
        ChannelResult r = amountMinor <= 0 ? direct(ctx, cmd) : channels.execute(ctx, cmd);
        return switch (r) {
            case ChannelResult.Pending p -> PayGateway.Result.ok(p.channelTradeNo());
            case ChannelResult.Succeeded s -> PayGateway.Result.ok(s.channelTradeNo());
            case ChannelResult.Failed f when f.code() == PayErrorCode.CHANNEL_CONNECT_FAILED ->
                    PayGateway.Result.retry(f.code().name());
            case ChannelResult.Failed f -> PayGateway.Result.rejected(f.channelMessage() != null ? f.channelMessage()
                    : f.code().name());
            case ChannelResult.Unknown u -> unproven(u.reason()) ? PayGateway.Result.fatal(u.reason())
                    : PayGateway.Result.retry(u.reason());
        };
    }

    public PayGateway.QueryResult query(String payChannel, String outTradeNo) {
        ChannelResult r = channels.execute(context(payChannel, null, null),
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

    /** 查退款（三态，对账回查用）。退成 → 已付；有这笔未退成（含渠道关闭、异常）→ 未付；没有这笔 → 没有；其余查不通 */
    public PayGateway.QueryResult queryRefund(String payChannel, String outRefundNo) {
        return switch (refundQuery(payChannel, outRefundNo)) {
            case ChannelResult.Succeeded s -> PayGateway.QueryResult.paid(amountOf(s), s.channelTradeNo(),
                    s.channelAmount() == null ? null : s.channelAmount().currency());
            case ChannelResult.Pending p -> PayGateway.QueryResult.unpaid();
            case ChannelResult.Failed f when f.code() == PayErrorCode.REFUND_CLOSED
                    || f.code() == PayErrorCode.REFUND_ABNORMAL -> PayGateway.QueryResult.unpaid();
            case ChannelResult.Failed f when f.code() == PayErrorCode.TRADE_NOT_FOUND -> PayGateway.QueryResult.notFound();
            case ChannelResult.Failed f -> PayGateway.QueryResult.failed();
            case ChannelResult.Unknown u -> PayGateway.QueryResult.failed();
        };
    }

    /**
     * 查退款（五态，退款执行与对账用）。渠道关闭是终态，凭据（退款单号、金额、币种）原样带出 —— 额度释放要核它们；
     * 「没有这笔」与查不通一样是未知：退款单不存在不能证明从未受理。
     */
    public RefundQueryResult queryRefundOutcome(String payChannel, String outRefundNo) {
        Optional<InProcessChannel> inProcess = capabilities.find(payChannel, InProcessChannel.class);
        if (inProcess.isPresent()) {
            // 进程内模拟银行自己会答五态：原样带回，连 null 与异常也同旧路由表一样交给调用方（偏差 0）
            return inProcess.get().refundOutcome(outRefundNo);
        }
        long now = System.currentTimeMillis();
        return switch (refundQuery(payChannel, outRefundNo)) {
            case ChannelResult.Succeeded s -> new RefundQueryResult(RefundQueryResult.Status.SUCCEEDED,
                    s.extra().get("channelStatus"), s.extra().getOrDefault("outRefundNo", outRefundNo), amountOf(s),
                    s.channelTradeNo(), s.channelAmount() == null ? null : s.channelAmount().currency(), now);
            case ChannelResult.Pending p -> new RefundQueryResult(RefundQueryResult.Status.PROCESSING, p.channelStatus(),
                    outRefundNo, 0, p.channelTradeNo(), null, now);
            case ChannelResult.Failed f when f.code() == PayErrorCode.REFUND_CLOSED -> new RefundQueryResult(
                    RefundQueryResult.Status.TERMINAL_FAILED, f.channelCode(), outRefundNo,
                    f.channelAmount() == null ? 0 : f.channelAmount().minor(), f.channelTradeNo(),
                    f.channelAmount() == null ? null : f.channelAmount().currency(), now);
            case ChannelResult.Failed f when f.code() == PayErrorCode.REFUND_ABNORMAL -> new RefundQueryResult(
                    RefundQueryResult.Status.MANUAL_REQUIRED, f.channelCode(), outRefundNo, 0, f.channelTradeNo(), null, now);
            case ChannelResult.Failed f -> RefundQueryResult.unknown(outRefundNo);
            case ChannelResult.Unknown u -> RefundQueryResult.unknown(outRefundNo);
        };
    }

    /**
     * 查退款不进管线（没有分类器），这里替它兜：处理器抛出的任何异常（超时、验签不过、渠道 4xx）都是「不知道」，
     * 与旧网关 {@code catch (RuntimeException) → UNKNOWN} 同口径。渠道没装（{@code BizException}）照旧抛给调用方。
     */
    private ChannelResult refundQuery(String payChannel, String outRefundNo) {
        ChannelAccountContext ctx = context(payChannel, null, null);
        try {
            return direct(ctx, new RefundQueryCommand(meta("REFUND_QUERY", outRefundNo), outRefundNo, null));
        } catch (RuntimeException uncertain) {
            log.warn("[pay-channel] 退款查询未知 channel={} no={} exception={}", payChannel, outRefundNo,
                    uncertain.getClass().getSimpleName());
            return new ChannelResult.Unknown("REFUND_QUERY_" + uncertain.getClass().getSimpleName());
        }
    }

    /** 不进管线，直达处理器（零金额、查退款）：没有调用记录行，传输层照样落原文 */
    private ChannelResult direct(ChannelAccountContext ctx, ChannelCommand cmd) {
        ChannelKey key = new ChannelKey(ctx.market(), ctx.payChannel(), ctx.accessMode(), cmd.scene(), cmd.operation());
        return handlers.resolve(key).handle(Invocation.start(key, ctx, cmd), cmd);
    }

    /** 渠道答了、但答案证明不了「没受理」：2xx 带错误码、验签不过、无法解析、未登记的渠道码 */
    private static boolean unproven(String reason) {
        return reason != null && (reason.startsWith(UNPROVEN) || reason.startsWith("BAD_SIGNATURE")
                || reason.startsWith("UNPARSEABLE") || reason.startsWith("UNMAPPED_CODE"));
    }

    private static long amountOf(ChannelResult.Succeeded s) {
        if (s.channelAmount() != null) {
            return s.channelAmount().minor();
        }
        String text = s.extra().get("amountMinor");
        return text == null ? 0L : Long.parseLong(text);
    }

    private ChannelAccountContext context(String payChannel, String currency, String subMchId) {
        require(payChannel);
        // 地区与收款商户在调用方接入 pay_merchant 路由（I3 / I5）之前还没有：null = 不限地区；接入模式取本部署装的那一种
        return contexts.from(ChannelRoute.of(payChannel, currency, subMchId));
    }

    private void require(String payChannel) {
        if (!supports(payChannel)) {
            // 通道名进日志不进响应：这是装配缺失，用户拿它做不了任何事（同旧路由表）
            log.error("支付通道未接入：{}（已装配：{}）", payChannel, capabilities.wired().keySet());
            throw BizException.of(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static InvocationMeta meta(String bizType, String bizNo) {
        return new InvocationMeta(null, null, null, null, null, SourceInfo.system(bizType, bizNo));
    }
}
