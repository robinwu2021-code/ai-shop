package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayScene;

/**
 * 对渠道的一次请求：<b>不可变命令</b>，可记录、可重放（补查、回调重放）、可审计（渠道接入统一封装设计 §2.2）。
 *
 * <p>I2 先落 CN 现有链路用到的五种；confirm / void / 进件 / 对账单 / 出款随用到它们的迭代（I6 / I7 / I8）加入 permits。
 */
public sealed interface ChannelCommand
        permits PayCommand, QueryCommand, CloseCommand, RefundCommand, RefundQueryCommand {

    ChannelOperation operation();

    InvocationMeta meta();

    /** 场景；与场景无关的操作是 {@link PayScene#ANY} */
    default PayScene scene() {
        return PayScene.ANY;
    }

    /** 这次请求涉及的金额（记录五段金额的第一段）；没有金额的操作为 null */
    default Money requestAmount() {
        return null;
    }

    /** 发给渠道的我方单号（收款单号 / 退款单号），幂等与检索都按它 */
    String outNo();
}
