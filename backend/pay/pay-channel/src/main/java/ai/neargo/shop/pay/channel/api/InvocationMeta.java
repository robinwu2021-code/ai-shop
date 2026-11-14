package ai.neargo.shop.pay.channel.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 调用元数据：谁、从哪、为哪张单（渠道接入统一封装设计 §2.4）。
 *
 * @param traceId 接入层生成，四向记录共用；补查沿用原追踪号
 */
public record InvocationMeta(String traceId, Long payOrderId, Long stlPaymentId, Long payRefundId,
                             Long payMerchantId, SourceInfo source) {

    public InvocationMeta {
        Objects.requireNonNull(source, "source");
        if (traceId == null || traceId.isBlank()) {
            traceId = newTraceId();
        }
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
