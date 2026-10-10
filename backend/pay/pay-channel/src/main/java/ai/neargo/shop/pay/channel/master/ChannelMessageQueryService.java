package ai.neargo.shop.pay.channel.master;

import java.util.List;
import java.util.Optional;

/**
 * 渠道报文的查询。<b>这张表的读者是排查故障的人，不是程序。</b>
 *
 * <h2>它为什么必须有个界面</h2>
 * V286 起报文已经在落库，而<b>没有任何地方能看</b> ——
 * 落了没人看的表等于没落。而出事那天最想看的恰恰是被拒掉的那几次：
 * 验签失败、回查失败、回查说没付、认领不到单号。
 * 那四条今天全是「log 一句然后回 FAIL」，通道那边一直重推，
 * 而运营问「它到底推了什么过来」时没人答得上。
 *
 * <h2>报文是脱敏后的</h2>
 * 签名、证书序列号、Authorization 都不进库 —— <b>所以这里不需要再脱敏一次</b>，
 * 但响应里要说清楚：它不能拿去重放验签，想验签去通道后台调原件。
 * 不说的话，第一个拿它去核签名的人会得出「我们的验签实现有 bug」这个结论。
 */
public interface ChannelMessageQueryService {

    /**
     * 分页查。<b>默认按时间倒序</b> —— 排查从最近一次开始，不是从第一次。
     *
     * @param payChannel 通道码；空 = 不限
     * @param msgType    CALLBACK / SEND；空 = 不限
     * @param outcome    结论码；空 = 不限。<b>排查时最常用的筛法</b>
     * @param bizNo      我方单号；空 = 不限。
     *                   ⚠️ 验签失败的行<b>拿不到单号</b>，按它筛会把最该看的那几行滤掉
     */
    Page page(String payChannel, String msgType, String outcome, String bizNo,
              long pageNo, long size);

    /** 单条详情（含完整报文与请求头）。查不到返回空 */
    Optional<MessageVO> find(String messageNo);

    /**
     * @param note 固定的口径说明。<b>端上必须显示</b> ——
     *             不显示的话，第一个拿报文去核签名的人会得出错误结论
     */
    record Page(List<MessageVO> records, long total, long pageNo, long size, String note) {
    }

    /**
     * @param bizNo   我方单号；<b>验签失败时为空</b>，那种行只能按时间和通道翻
     * @param reason  拒绝或失败的原因，直接显示给运营
     * @param payload 脱敏并截断后的报文
     */
    record MessageVO(String messageNo, String payChannel, String msgType, String api,
                     String bizNo, String paymentNo, String outcome, String reason,
                     String payload, String headers, Long createdAt) {
    }
}
