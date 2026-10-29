package ai.neargo.shop.pay.channel.platform;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.capability.OnlinePayCapability;
import ai.neargo.shop.pay.channel.capability.TransactionCapability;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.spi.pay.PayInstruction;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 进程内渠道（测试 TEST、开发假网关 STUB、零元免单 FREE）的能力实现（TDD-支付服务-W2渠道层补完 S5）。
 *
 * <p>这三家<b>没有协议、没有 HTTP</b>：对端是进程里的模拟银行（{@code TestPayGateway} 等，仍是 {@link PayGateway} 的形状 ——
 * 40 余个 CN 场景测试以它为「测试银行」间谍其调用次数，那层形状不能动）。这里把模拟银行接到能力接口上，结果映射与
 * I2 的过渡适配逐字一致：下单被拒 → 明确失败；查不通 → 不明；退款受理 → 处理中；退款可重试 / 没被明确拒绝 → 不明（只查不重发）。
 *
 * <p>真渠道（微信、支付宝）不走这里，走各自的处理器 + 协议 + 记录型客户端。接入模式恒为 {@code PLATFORM_SUB}（平台自己的账户）。
 */
public final class InProcessChannel implements OnlinePayCapability, TransactionCapability {

    private final PayGateway bank;

    public InProcessChannel(PayGateway bank) {
        this.bank = bank;
    }

    @Override
    public String payChannel() {
        return bank.payChannel();
    }

    @Override
    public AccessMode accessMode() {
        return AccessMode.PLATFORM_SUB;
    }

    @Override
    public Set<PayScene> onlineScenes() {
        return EnumSet.of(PayScene.ONLINE_SDK);
    }

    @Override
    public Set<ChannelOperation> transactionOperations() {
        Set<ChannelOperation> operations = EnumSet.of(ChannelOperation.QUERY, ChannelOperation.REFUND,
                ChannelOperation.REFUND_QUERY);
        if (bank instanceof TestPayGateway) {
            operations.add(ChannelOperation.CLOSE);
        }
        return operations;
    }

    @Override
    public boolean supportsRefundCurrency(String currency) {
        return bank.supportsRefundCurrency(currency);
    }

    /** The stateful test bank has no external signing account; real and stateless stub channels do not qualify. */
    @Override
    public boolean supportsFrozenAccount(ChannelRoute route) {
        return bank instanceof TestPayGateway;
    }

    @Override
    public ChannelResult payOnline(Invocation inv, PayCommand c) {
        inv.markSent();
        PayGateway.PrepayResult r = bank.prepay(new PayGateway.PrepayCommand(c.outTradeNo(), c.amount().minor(),
                c.amount().currency(), c.subject(), "JSAPI", inv.account().subMerchantId(), c.payerId()));
        if (r == null) {
            throw new ChannelTransportException(ChannelTransportException.Kind.UNPARSEABLE, "下单没有回执");
        }
        if (!r.success()) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null, r.message(), false);
        }
        PayInstruction instruction = r.params() == null || r.params().isEmpty() ? PayInstruction.none()
                : new PayInstruction.AppSdkParams(r.params());
        return new ChannelResult.Pending(instruction, r.tradeNo());
    }

    @Override
    public ChannelResult query(Invocation inv, QueryCommand c) {
        inv.markSent();
        PayGateway.QueryResult r = bank.query(c.outTradeNo());
        if (r == null || !r.ok()) {
            return new ChannelResult.Unknown("QUERY_FAILED");
        }
        if (!r.found()) {
            return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "渠道没有这笔", false);
        }
        if (!r.paid()) {
            String state = bank instanceof TestPayGateway test && test.isClosed(c.outTradeNo()) ? "CLOSED" : null;
            return new ChannelResult.Pending(PayInstruction.none(), r.tradeNo(), state);
        }
        return new ChannelResult.Succeeded(r.tradeNo(), money(r, inv.account()), null, Map.of());
    }

    @Override
    public ChannelResult close(Invocation inv, CloseCommand c) {
        if (!(bank instanceof TestPayGateway test)) {
            return ChannelCapability.unsupported(ChannelOperation.CLOSE);
        }
        inv.markSent();
        return test.closeOrder(c.outTradeNo()) ? new ChannelResult.Succeeded(null, null, null, Map.of())
                : new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null, "渠道订单已付，不能关闭", false);
    }

    @Override
    public ChannelResult refund(Invocation inv, RefundCommand c) {
        inv.markSent();
        PayGateway.Result r = bank.refund(new PayGateway.TxContext(inv.account().subMerchantId(),
                        c.originChannelTradeNo(), c.originOutTradeNo(), c.originAmount().minor(), c.refundAmount().currency()),
                c.refundAmount().minor(), c.outRefundNo(), c.reason());
        if (r == null || (!r.success() && r.retryable())) {
            return new ChannelResult.Unknown("REFUND_SEND_UNKNOWN" + reason(r));
        }
        if (!r.success()) {
            return r.definitiveRejection()
                    ? new ChannelResult.Failed(PayErrorCode.REFUND_REJECTED, null, r.message(), false)
                    : new ChannelResult.Unknown("REFUND_SEND_UNPROVEN" + reason(r));
        }
        return new ChannelResult.Pending(PayInstruction.none(), r.providerNo());
    }

    /**
     * 查退款问的是模拟银行的三态查询（与旧网关的默认五态推导同源）：查不通 → 不明；没有这笔 → {@code TRADE_NOT_FOUND}；
     * 退成 → 成功（带金额与币种）；有这笔未退成 → 处理中。原始状态码模拟银行没有，如实为空。
     */
    @Override
    public ChannelResult queryRefund(Invocation inv, RefundQueryCommand c) {
        inv.markSent();
        PayGateway.QueryResult r = bank.queryRefund(c.outRefundNo());
        if (r == null || !r.ok()) {
            return new ChannelResult.Unknown("QUERY_FAILED");
        }
        if (!r.found()) {
            return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "渠道没有这笔退款", false);
        }
        if (!r.paid()) {
            return new ChannelResult.Pending(PayInstruction.none(), r.tradeNo());
        }
        return new ChannelResult.Succeeded(r.tradeNo(), money(r, inv.account()), null,
                Map.of("outRefundNo", c.outRefundNo(), "amountMinor", String.valueOf(r.amountMinor())));
    }

    /**
     * 五态查退款：模拟银行自己的五态答案，<b>原样</b>带回（与旧路由表 {@code router.of(ch).queryRefundOutcome} 逐字相同）。
     * 三态与五态是两个问题 —— 「没有这笔」在三态里是可以说的，在五态里只能是未知 —— 进程内银行两种都会答，问哪个就答哪个，
     * 不从一个推另一个（场景测试会单独给五态打桩）。真渠道没有这个旁路，五态由 {@code queryRefund} 的四态结果译出。
     */
    public RefundQueryResult refundOutcome(String outRefundNo) {
        return bank.queryRefundOutcome(outRefundNo);
    }

    private static Money money(PayGateway.QueryResult r, ChannelAccountContext account) {
        String currency = r.currency() != null ? r.currency() : account.currency();
        return currency == null ? null : new Money(r.amountMinor(), currency);
    }

    private static String reason(PayGateway.Result r) {
        return r == null || r.message() == null ? "" : ": " + r.message();
    }
}
