package ai.neargo.shop.pay.channel.fault;

import java.time.Duration;

/**
 * 一个「渠道 × 操作」的容错策略（Strategy，渠道接入统一封装设计 §8.1）。
 *
 * @param readTimeout          连上之后等响应的上限；超了按 {@code Unknown} 处理
 * @param maxRetries           额外重发次数（不含第一次）
 * @param retryOnUncertain     结果不明（超时、5xx）时能否再发 —— 只有纯读可以
 * @param backoff              两次发送之间的等待
 * @param maxConcurrent        这个渠道同时在途的上限（隔离：一个渠道变慢不拖垮别的）
 * @param failureRateThreshold 熔断：窗口内失败率（%）达到它即打开
 * @param minimumCalls         熔断：窗口内至少这么多次才计算失败率
 * @param openWait             熔断打开后多久转半开
 */
public record FaultPolicy(Duration readTimeout, int maxRetries, boolean retryOnUncertain, Duration backoff,
                          int maxConcurrent, float failureRateThreshold, int minimumCalls, Duration openWait) {

    /** 写进调用记录的摘要（{@code pay_channel_call_log.fault_policy}，128 字以内） */
    public String summary() {
        return "timeout=" + readTimeout.toMillis() + "ms,retries=" + maxRetries
                + ",resendOnUncertain=" + retryOnUncertain + ",concurrent=" + maxConcurrent;
    }
}
