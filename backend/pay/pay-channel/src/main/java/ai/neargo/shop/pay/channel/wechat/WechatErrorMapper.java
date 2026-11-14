package ai.neargo.shop.pay.channel.wechat;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import java.util.Optional;

/**
 * 微信错误码 → 统一码（渠道接入统一封装设计 §6.4）。<b>只登记实测确认过的</b>；没登记的由分类器按「未知」处理 ——
 * 把没见过的码当失败，退款就可能被重发。
 *
 * <p>{@code ORDER_NOT_EXIST}：拿不存在的单号打真接口实测（2026-09-04），HTTP 404 + <b>带下划线</b>。此前判的是
 * {@code ORDERNOTEXIST}（抄自旧文档），永远匹配不上 —— 后果不是报错，是「通道确实没有这笔」被降级成「查询失败」，
 * 对账那条轴永远关不掉这种单。去掉下划线再比，两种拼法都认。
 *
 * <p>退款查询的 {@code RESOURCE_NOT_EXISTS} <b>刻意不登记</b>：退款单不存在不能证明从未受理，不能据以关单。
 */
public final class WechatErrorMapper implements ChannelErrorMapper {

    @Override
    public String payChannel() {
        return WechatV3Client.CHANNEL;
    }

    @Override
    public Optional<MappedError> map(ChannelOperation op, int httpStatus, String channelCode) {
        if (op == ChannelOperation.QUERY && norm(channelCode).equals("ORDERNOTEXIST")) {
            return Optional.of(new MappedError(PayErrorCode.TRADE_NOT_FOUND, false));
        }
        return Optional.empty();
    }

    private static String norm(String code) {
        return code == null ? "" : code.replace("_", "");
    }
}
