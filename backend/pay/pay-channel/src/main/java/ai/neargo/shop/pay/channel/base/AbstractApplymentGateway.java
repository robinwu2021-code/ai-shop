package ai.neargo.shop.pay.channel.base;

import ai.neargo.shop.pay.channel.ChannelClient;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 进件网关的骨架。与 {@link AbstractPayGateway} 同一条路数：**加通道 = 写子类填空**。
 *
 * <p><b>这一层最重要的职责是「不让结算账号漏出去」。</b>
 * 明文结算账号只在 {@code submit} 这一次调用里存在 —— 不落库、不进日志
 * （接口注释里写着，库里只留 {@code settle_account_masked}）。
 * 把日志写在基类而不是让每个子类自己打，是因为<b>漏一次的代价是一次数据泄露</b>，
 * 而「记得别打某个字段」是最容易忘的那种约定。
 *
 * <p><b>模板方法 final</b>，理由同交易侧：子类能覆写整个方法，就能跳过脱敏那一步。
 */
public abstract class AbstractApplymentGateway implements PayApplymentGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractApplymentGateway.class);

    protected final ChannelClient client;

    protected AbstractApplymentGateway(ChannelClient client) {
        this.client = client;
    }

    @Override
    public final String submit(SubmitCommand cmd) {
        /*
         * **只打不敏感的那几个字段。** 主体名、联系人、结算账号一律不打 ——
         * 前两个是个人信息，后一个是钱的去处。日志会被采集、转发、留存。
         */
        log.info("[applyment] {} 提交进件 entity={} legalForm={} settleType={} 资质 {} 张",
                payChannel(), cmd.entityNo(), cmd.legalForm(), cmd.settleAccountType(),
                cmd.licenses() == null ? 0 : cmd.licenses().size());

        Map<String, Object> resp = client.post(submitApi(), buildSubmit(cmd));
        String applyNo = applyNoOf(resp);
        if (applyNo == null || applyNo.isBlank()) {
            /*
             * 拿不到申请单号 = 后面查不了结果。**这里必须炸**，
             * 不能返回空串让上层落库 —— 落进去之后那一行永远停在「审核中」，
             * 而没有任何东西能推动它。
             */
            throw new ChannelClient.ChannelException(
                    payChannel() + " 进件回执里没有申请单号，无法后续查询", false);
        }
        log.info("[applyment] {} 已受理 entity={} apply={}", payChannel(), cmd.entityNo(), applyNo);
        return applyNo;
    }

    @Override
    public final ApplymentResult query(String channelApplyNo) {
        Map<String, Object> resp = client.post(queryApi(channelApplyNo), Map.of());
        ApplymentResult r = parseResult(resp);
        log.info("[applyment] {} 查询 apply={} → {}{}", payChannel(), channelApplyNo, r.status(),
                r.rejectReason() == null ? "" : "（" + r.rejectReason() + "）");
        return r;
    }

    // ------------------------------------------------------------ 子类填空

    /** 提交接口坐标。 */
    protected abstract String submitApi();

    /** 查询接口坐标。微信是路径带单号，支付宝是接口名 + 参数。 */
    protected abstract String queryApi(String channelApplyNo);

    /** 把进件资料映射成通道要的报文。**这是各家差别最大的地方**。 */
    protected abstract Map<String, Object> buildSubmit(SubmitCommand cmd);

    /** 回执里的申请单号。 */
    protected abstract String applyNoOf(Map<String, Object> resp);

    /**
     * 回执 → 我方状态。**驳回必须带原因** ——
     * 没有原因商家只能反复重提，而每一次都会被同一条理由拒掉。
     */
    protected abstract ApplymentResult parseResult(Map<String, Object> resp);
}
