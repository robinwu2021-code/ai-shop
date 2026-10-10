package ai.neargo.shop.spi.pay;

import java.util.Map;

/**
 * 渠道报文落库（V286）。回调入口在 trade 侧，报文表在 pay 侧，故走 port。
 *
 * <h2>为什么是「先落一行、再回填结论」两步</h2>
 * 一步（处理完了再记）的写法丢掉的正是最该留的那些：
 * 处理中途抛异常的那一次，什么都不会记。而<b>那次恰恰是有人要来问的那次</b>。
 *
 * <p>所以第一步在<b>任何处理之前</b>落 {@code RECEIVED}，第二步回填结论。
 * 停在 {@code RECEIVED} 的行不是脏数据，是「收到了但没处理完」这句话本身。
 *
 * <p>两步都<b>不抛异常、不影响主链路</b>：为了记一条排查用的记录
 * 而让一笔真实收款失败，是本末倒置。
 */
public interface PayMessagePort {

    /**
     * 收到回调，处理之前先落一行。
     *
     * @param rawBody 原始报文。<b>此刻还没验签</b> —— 实现只存指纹与短前缀，
     *                不存全文：这个端点公网可达，任何人都能往它 POST 任意内容
     * @return 报文号；落库失败返回 null（后续回填会自己跳过）
     */
    String callbackReceived(String payChannel, String api,
                            Map<String, String> headers, String rawBody);

    /**
     * 回填结论。
     *
     * @param outcome  {@code ACCEPTED} / {@code REJECTED}
     * @param reason   拒绝原因，直接显示给运营
     * @param payload  验签通过后解析出来的报文；未通过时传 null
     */
    void callbackSettled(String messageNo, String outcome, String reason,
                         String bizNo, String paymentNo, Map<String, ?> payload);

    /** {@link #callbackSettled} 的 outcome：验过签、回查过、账也落了 */
    String ACCEPTED = "ACCEPTED";
    /** {@link #callbackSettled} 的 outcome：我方拒绝并已回 FAIL */
    String REJECTED = "REJECTED";
}
