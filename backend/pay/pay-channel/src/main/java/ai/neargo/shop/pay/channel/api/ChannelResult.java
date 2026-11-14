package ai.neargo.shop.pay.channel.api;

import ai.neargo.shop.spi.pay.PayInstruction;
import java.util.Map;

/**
 * 统一结果：<b>四态，不是两态</b>（渠道接入统一封装设计 §2.3）。
 *
 * <p>现有 {@code PayGateway.Result} 把「超时」折进了 retry —— 对退款就意味着可能重复发。
 * 这里把「不知道成没成」单列为 {@link Unknown}，由补查处理，<b>绝不当失败</b>。
 */
public sealed interface ChannelResult {

    /** 渠道明确成功（含同步扣款成功） */
    record Succeeded(String channelTradeNo, Money channelAmount, Long feeMinor, Map<String, String> extra)
            implements ChannelResult {
        public Succeeded {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }
    }

    /**
     * 已受理、等用户或渠道下一步。
     *
     * @param instruction   给端上的下一步（唤起 SDK、跳转、表单、二维码；没有就是 {@link PayInstruction#none()}）
     * @param channelStatus 渠道原始状态码（如微信退款 {@code PROCESSING}）；查询类结果原样带回，进件 / 退款回查要落库
     */
    record Pending(PayInstruction instruction, String channelTradeNo, String channelStatus) implements ChannelResult {
        public Pending {
            instruction = instruction == null ? PayInstruction.none() : instruction;
        }

        public Pending(PayInstruction instruction, String channelTradeNo) {
            this(instruction, channelTradeNo, null);
        }
    }

    /**
     * 渠道明确失败。{@code retryable} 只表示「换个时间再发同一命令是否可能成功」。
     *
     * <p>{@code channelTradeNo} / {@code channelAmount}：明确失败也可能带着渠道的凭据 —— 退款被渠道关闭（微信 {@code CLOSED}）
     * 是终态，退款单号与金额是「这笔确实关了」的证据，对账与退款额度释放要它（W2 S5）。没有就是 null。
     */
    record Failed(PayErrorCode code, String channelCode, String channelMessage, boolean retryable,
                  String channelTradeNo, Money channelAmount) implements ChannelResult {

        public Failed(PayErrorCode code, String channelCode, String channelMessage, boolean retryable) {
            this(code, channelCode, channelMessage, retryable, null, null);
        }
    }

    /** 不知道成没成（超时、断连、5xx、响应无法解析、未登记渠道码） */
    record Unknown(String reason) implements ChannelResult {
    }

    default String outcomeClass() {
        return switch (this) {
            case Succeeded s -> "SUCCEEDED";
            case Pending p -> "PENDING";
            case Failed f -> "FAILED";
            case Unknown u -> "UNKNOWN";
        };
    }
}
