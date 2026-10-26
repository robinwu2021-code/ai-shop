package ai.neargo.shop.pay.acquiring.statemachine;

/** 支付单事件。{@link #SUCCEEDED} 只能由结果入口发出（{@code SingleOutcomeWriterTest} 守） */
public enum PayOrderEvent {
    /** 开了一次通道尝试 */
    ATTEMPT_OPENED,
    /** 渠道确认已付（结果入口） */
    SUCCEEDED,
    /** 渠道确认失败 */
    FAILED,
    /** 关单（超时 / 调用方取消），关前已查单确认未付 */
    CLOSED,
    /** 部分退款成功 / 全额退完 */
    PARTIALLY_REFUNDED,
    REFUNDED
}
