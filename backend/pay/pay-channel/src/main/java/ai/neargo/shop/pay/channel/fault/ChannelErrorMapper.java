package ai.neargo.shop.pay.channel.fault;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import java.util.Optional;

/**
 * 渠道原始错误码 → 统一码（Adapter，渠道接入统一封装设计 §6.4）。每渠道一份。
 * <b>未登记的码返回空</b>，由分类器按 {@code Unknown} 处理 —— 把没见过的码当失败，退款就可能被重发。
 */
public interface ChannelErrorMapper {

    record MappedError(PayErrorCode code, boolean retryable) {
    }

    String payChannel();

    Optional<MappedError> map(ChannelOperation op, int httpStatus, String channelCode);
}
