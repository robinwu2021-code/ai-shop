package ai.neargo.shop.spi.notify;

/**
 * 域 → channel：把一条 App 推送交给聚合商（ADR-018：个推/uni-push 2.0，免费档起步）。
 *
 * <p>{@code clientId} 是聚合商的设备标识（uni-push 下即个推 cid），厂商路由
 * （小米/华为/OPPO/vivo/荣耀/APNs）由聚合商按设备自动完成，领域代码不感知厂商。
 *
 * <p><b>提醒级别是业务概念，放在接口上</b>：「新订单要响铃」是商家域的需求，
 * 不是通道参数的搬运 —— 通道把它翻译成厂商的 channel/category/importance。
 *
 * <p><b>失败语义</b>：抛 {@link PushException}。推送是尽力而为的加速通道
 * （站内信才是必达记录），调用方留痕后放行，不得让事件重试。
 */
public interface PushPort {

    /** 常规通知：横幅 + 角标。 */
    String LEVEL_NORMAL = "NORMAL";
    /** 高优先提醒：响铃/震动（B 端新订单）。免费档下受厂商离线配额约束（ADR-018）。 */
    String LEVEL_RING = "RING";

    /**
     * @param clientId 设备标识（个推 cid）
     * @param link     点开后落到的应用内路径（如 {@code /pages/orders/index}），
     *                 通道负责包装成厂商 intent/payload
     * @param level    {@link #LEVEL_NORMAL} / {@link #LEVEL_RING}
     */
    SendResult push(String clientId, String title, String body, String link, String level);

    class PushException extends RuntimeException {
        /** 网络类失败可重试；聚合商业务码（cid 失效、配额用尽）重试也是同一个结果。 */
        private final boolean retryable;

        public PushException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
