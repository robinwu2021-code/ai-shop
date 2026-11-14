package ai.neargo.shop.pay.channel.api.command;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayScene;
import java.time.Instant;
import java.util.Objects;

/**
 * 收款。
 *
 * @param payerId  JSAPI 的 openid 等付款人标识
 * @param authCode 仅 IN_STORE_SCAN_CUSTOMER：顾客付款码
 */
public record PayCommand(InvocationMeta meta, PayScene scene, Money amount, String subject, String outTradeNo,
                         Instant expireAt, String payerId, String authCode) implements ChannelCommand {

    public PayCommand {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(outTradeNo, "outTradeNo");
    }

    @Override
    public ChannelOperation operation() {
        return ChannelOperation.PAY;
    }

    @Override
    public Money requestAmount() {
        return amount;
    }

    @Override
    public String outNo() {
        return outTradeNo;
    }
}
