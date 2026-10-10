package ai.neargo.shop.pay.channel;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.mapper.ChannelMappers;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 渠道报文落库（V286）。
 *
 * <h2>两条规矩，反过来做就等于没做</h2>
 *
 * <p><b>一、独立事务。</b>每个方法都是 {@code REQUIRES_NEW} ——
 * 报文要在调用方的事务之外落。回调处理失败时业务要回滚，
 * 而<b>报文必须留下</b>：处理失败的那一次，恰恰是最需要报文的那一次。
 * 跟着调用方事务走的话，出事的报文全被一起回滚掉，
 * 而表里剩下的全是成功的那些 —— 一张永远只记录顺利情况的表。
 *
 * <p><b>二、落库失败绝不影响主链路。</b>这张表没有任何业务逻辑读它，
 * 为了记一条排查用的记录而让一笔真实收款失败，是本末倒置。
 * 所以每个方法都吞异常并 {@code log.error} —— <b>这是少数几个该吞异常的地方</b>，
 * 理由要写在这儿，免得下一个人「顺手」把它改成往上抛。
 */
@Service
public class ChannelMessageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageRecorder.class);

    private final ChannelMappers.ChannelMessageMapper mapper;

    /**
     * 报文保留多少天。
     *
     * <p>做成配置项而不是 {@code pay_setting} 里的一条：那张表在 pay-domain，
     * 而 pay-domain 依赖本模块 —— 去读它就是把依赖倒过来。
     * 保留期是<b>部署形态的参数</b>（磁盘多大、要不要留久点），
     * 不是运营会去调的业务设置，配置项是它该待的地方。
     */
    private final int retentionDays;

    public ChannelMessageRecorder(ChannelMappers.ChannelMessageMapper mapper,
                                  @Value("${shop.pay.message-retention-days:90}") int retentionDays) {
        this.mapper = mapper;
        this.retentionDays = retentionDays;
    }

    /**
     * 删掉超过保留期的报文。<b>不走 MyBatis-Plus 的逻辑删除</b>：
     * 逻辑删除只是把 {@code deleted} 置 1，表还是那么大 ——
     * 而这个任务存在的全部理由就是让表别一直长。
     *
     * @param batch 一轮上限，防止保留期被手滑改小时一次删空
     * @return 实际删掉的行数
     */
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public int purgeOlderThanRetention(int batch) {
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        return DataScopeContext.executeWithoutScope(() -> mapper.delete(
                Wrappers.<StlChannelMessage>lambdaQuery()
                        .lt(StlChannelMessage::getCreatedAt, before)
                        .last("LIMIT " + batch)));
    }

    /**
     * 收到回调，<b>在处理之前</b>先落一行 {@link StlChannelMessage#RECEIVED}。
     *
     * @param headers 请求头。按键名脱敏后存 —— 签名与 Authorization 不进库
     * @param rawBody 原始报文。<b>此刻还没验签</b>，所以只存指纹与前缀
     *                （见 {@link PayloadMasker#unverified}）
     * @return 报文号，供后续 {@link #settle} 回填结论；落库失败返回 null
     */
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public String received(String payChannel, String api,
                           Map<String, String> headers, String rawBody) {
        try {
            StlChannelMessage m = new StlChannelMessage();
            m.setMessageNo(BizKey.next(BizKey.CHANNEL_MESSAGE));
            m.setPayChannel(payChannel);
            m.setMsgType(StlChannelMessage.CALLBACK);
            m.setApi(api);
            m.setOutcome(StlChannelMessage.RECEIVED);
            m.setHeaders(PayloadMasker.mask(headers));
            m.setPayload(PayloadMasker.unverified(rawBody));
            DataScopeContext.executeWithoutScope(() -> mapper.insert(m));
            return m.getMessageNo();
        } catch (RuntimeException e) {
            log.error("[channel-message] 回调报文落库失败，主链路继续：{} {}", payChannel, api, e);
            return null;
        }
    }

    /**
     * 回填这条回调的结论。
     *
     * @param messageNo 为 null 时直接返回 —— 落库那步失败过，没有行可以回填
     * @param payload   验签通过后解析出来的报文。<b>到这一步才敢按键存</b>：
     *                  内容已经证明来自通道，不再是公网任意输入
     */
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public void settle(String messageNo, String outcome, String reason,
                       String bizNo, String paymentNo, Map<String, ?> payload) {
        if (messageNo == null) {
            return;
        }
        try {
            StlChannelMessage patch = new StlChannelMessage();
            patch.setOutcome(outcome);
            patch.setReason(reason);
            patch.setBizNo(bizNo);
            patch.setPaymentNo(paymentNo);
            if (payload != null) {
                patch.setPayload(PayloadMasker.mask(payload));
            }
            DataScopeContext.executeWithoutScope(() -> mapper.update(patch,
                    Wrappers.<StlChannelMessage>lambdaUpdate()
                            .eq(StlChannelMessage::getMessageNo, messageNo)));
        } catch (RuntimeException e) {
            log.error("[channel-message] 回填结论失败，主链路继续：{}", messageNo, e);
        }
    }

    /**
     * 我方发给通道的一次调用。<b>发完才记</b>（成败都记），
     * 与回调相反 —— 发送这一侧「发出去了但没记上」不会丢线索：
     * 通道的回执就在同一个方法里，拿到才有话可说。
     */
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public void sent(String payChannel, String api, String bizNo,
                     boolean ok, String reason, Map<String, ?> body) {
        try {
            StlChannelMessage m = new StlChannelMessage();
            m.setMessageNo(BizKey.next(BizKey.CHANNEL_MESSAGE));
            m.setPayChannel(payChannel);
            m.setMsgType(StlChannelMessage.SEND);
            m.setApi(api);
            m.setBizNo(bizNo);
            m.setOutcome(ok ? StlChannelMessage.OK : StlChannelMessage.FAILED);
            m.setReason(reason);
            m.setPayload(PayloadMasker.mask(body));
            DataScopeContext.executeWithoutScope(() -> mapper.insert(m));
        } catch (RuntimeException e) {
            log.error("[channel-message] 发送报文落库失败，主链路继续：{} {}", payChannel, api, e);
        }
    }
}
