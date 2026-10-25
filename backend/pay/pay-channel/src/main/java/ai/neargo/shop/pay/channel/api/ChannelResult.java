package ai.neargo.shop.pay.channel.api;

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

    /** 已受理、等用户或渠道下一步；{@code instruction} 是给端上的参数（唤起收银台、二维码…） */
    record Pending(Map<String, Object> instruction, String channelTradeNo) implements ChannelResult {
        public Pending {
            instruction = instruction == null ? Map.of() : Map.copyOf(instruction);
        }
    }

    /** 渠道明确失败。{@code retryable} 只表示「换个时间再发同一命令是否可能成功」 */
    record Failed(PayErrorCode code, String channelCode, String channelMessage, boolean retryable)
            implements ChannelResult {
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
