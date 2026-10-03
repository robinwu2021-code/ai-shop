package ai.neargo.shop.spi.notify;

/**
 * 通道回执。短信与邮件共用。
 *
 * <p><b>用返回值而不是让实现暴露 {@code lastMessageId()} 这类 getter</b>：
 * 后者是有状态的，两个线程同时发时会互相串号 —— 而发送记录里串了号，
 * 排查时就会拿着 A 的流水号去问 B 那条为什么没到，比没有记录更误导。
 *
 * @param providerMsgId 通道流水号（阿里云 {@code BizId} / 邮件 {@code Message-ID}）。
 *                      <b>找通道对账靠它</b>，桩实现为空
 * @param templateCode  短信为实际用的模板号；邮件留空（主题另记）
 */
public record SendResult(String providerMsgId, String templateCode) {

    public static SendResult none() {
        return new SendResult(null, null);
    }

    public static SendResult of(String providerMsgId) {
        return new SendResult(providerMsgId, null);
    }

    public static SendResult of(String providerMsgId, String templateCode) {
        return new SendResult(providerMsgId, templateCode);
    }
}
