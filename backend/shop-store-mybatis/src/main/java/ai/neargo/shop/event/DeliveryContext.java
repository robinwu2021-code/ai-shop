package ai.neargo.shop.event;

/**
 * 当前线程正在投递的事件号。投递方（提交后通知、发件箱投递，以后的 MQ 消费适配器）在调消费者前设上，
 * 外部通道据此按“事件号 + 通道 + 收件人”去重（{@code EventIdempotency#firstSend}，ADR-067 决策 8）。
 */
public final class DeliveryContext {

    private static final ThreadLocal<String> EVENT_NO = new ThreadLocal<>();

    private DeliveryContext() { }

    /** 正在投递的事件号；不在投递里（运营端测试发送、后台任务）为 null。 */
    public static String eventNo() {
        return EVENT_NO.get();
    }

    public static void run(String eventNo, Runnable action) {
        String outer = EVENT_NO.get();
        EVENT_NO.set(eventNo);
        try {
            action.run();
        } finally {
            if (outer == null) {
                EVENT_NO.remove();
            } else {
                EVENT_NO.set(outer);
            }
        }
    }
}
