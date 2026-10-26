package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.EnumSet;
import java.util.Set;

/**
 * 交易之后：查单、关单、退款、查退款。
 *
 * <p>结果约定（调用方据此判，不能混）：
 * <ul>
 *   <li>查单：已付 {@code Succeeded}（带渠道金额）· 有这笔未付 {@code Pending} · <b>渠道说没有这笔</b>
 *       {@code Failed(TRADE_NOT_FOUND)}（可以安全关单）· 查不通 {@code Unknown}（<b>不可</b>据以关单）</li>
 *   <li>退款：受理 {@code Pending}（带渠道退款单号）· 明确拒绝 {@code Failed(REFUND_REJECTED)} · 发出去但不确定 {@code Unknown}</li>
 *   <li>查退款：退成 {@code Succeeded} · 处理中 {@code Pending} · 渠道关闭 {@code Failed(REFUND_CLOSED)}（带退款单号与金额）·
 *       异常待人工 {@code Failed(REFUND_ABNORMAL)} · 渠道没有这笔 {@code Failed(TRADE_NOT_FOUND)} · 查不通 {@code Unknown}</li>
 * </ul>
 */
public interface TransactionCapability extends ChannelCapability {

    /** 实际支持的操作；不在里面的不登记处理器（调到即 {@code CHANNEL_UNSUPPORTED}） */
    default Set<ChannelOperation> transactionOperations() {
        return EnumSet.of(ChannelOperation.QUERY, ChannelOperation.CLOSE, ChannelOperation.REFUND,
                ChannelOperation.REFUND_QUERY);
    }

    ChannelResult query(Invocation inv, QueryCommand command);

    ChannelResult close(Invocation inv, CloseCommand command);

    ChannelResult refund(Invocation inv, RefundCommand command);

    ChannelResult queryRefund(Invocation inv, RefundQueryCommand command);

    /** 现有国内退款路线只认人民币；其他币种要实现显式放开（I3 偏差 2） */
    default boolean supportsRefundCurrency(String currency) {
        return "CNY".equals(currency);
    }
}
