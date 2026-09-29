package ai.neargo.shop.spi.notify;

import java.util.List;

/**
 * 域 → 通道：把一件**运营需要立刻知道的事**交给通道去送。
 *
 * <p><b>为什么要这条 Port</b>：触发点在 platform 域（有人提交了入驻意向），
 * 而送达能力在 message 域。两个业务域不许互相依赖（ArchitectureTest：
 * 「业务域之间不得互相依赖，跨域只走 spi 包的 Port/Event」），
 * 所以在这里开一条最小能力，实现放 message 域的 {@code .port} 包里做薄转发。
 *
 * <p><b>按事给方法，不给通用的 {@code send(title, body)}</b>：理由与
 * {@link WxSubscribePort} 相同 —— 通用签名只会把「这条消息长什么样」的决定
 * 推给调用方，于是每个域各写一份排版，而排版是通道的事。
 * 再来第二种事就新增一个方法。
 *
 * <p><b>失败语义：不抛</b>。通知是尽力而为的，不能让它把主流程（报名落库）拖回滚。
 * 发不出去由实现自己留痕（{@code sys_notify_log}）—— 否则「消息没发出去」
 * 这件事没有任何症状。
 */
public interface OpsAlertPort {

    /**
     * 有新的入驻意向。
     *
     * <p>两条出口由实现决定：运营端的站内消息（未读角标）与企业微信群机器人
     * （人不在运营端时也能知道）。
     *
     * @param apply        意向内容。<b>手机号传原始值</b> —— 掩不掩码是通道的决定，
     *                     群里要掩、运营端点进去要看全号，两边口径不同
     * @param opsReceivers 站内消息发给谁（运营账号号）。**谁该收由 platform 定** ——
     *                     它才知道运营账号表长什么样；空列表就只发群机器人
     * @param reviewLink   运营端审核台的地址；空 = 没配域名，实现据此不拼链接
     */
    void newMerchantApply(NewApply apply, List<String> opsReceivers, String reviewLink);

    /**
     * @param industry     行业**码**（如 RETAIL）。通道要显示成人话的话自己翻译，
     *                     这里不传译好的名字 —— 那会让同一个码在不同通道翻出不同的词
     * @param contactPhone 原始手机号，未掩码
     */
    record NewApply(String applyNo, String shopName, String industry,
                    String category, String contactPhone) {
    }
}
