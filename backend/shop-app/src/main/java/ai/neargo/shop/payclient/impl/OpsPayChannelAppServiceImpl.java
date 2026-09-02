package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.channel.master.PayChannelRateService;
import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 通道主数据的运营端实现。<b>两种部署形态都装配它。</b>
 *
 * <h2>2026-09-02：这里曾经挂着 embedded 条件，把生产打挂了</h2>
 * 原来的写法是
 * {@code @ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded")}，
 * 注释写着「独立形态的远程实现待 M1 之后单独做」——
 * <b>而那个远程实现始终没做，生产的值又正好是 standalone</b>。
 * 于是上线那一刻这个 bean 不装配，而 {@code OpsPayChannelController} 要它，
 * Spring 上下文起不来，服务反复重启，只能回滚。
 *
 * <p><b>本地 1632 个测试全绿</b> —— 因为 {@code matchIfMissing = true}
 * 让测试跑的是 embedded 那一半，而生产跑的是另一半。
 * 那句注释本身就是缺陷的自白：明知远程实现还没有，却仍然加了条件，
 * 等于<b>主动把 standalone 那一半变成「没有任何 bean」</b>。
 *
 * <p><b>为什么摘掉条件是安全的</b>：D2 切库还没做，
 * 两种形态今天共用同一个库，standalone 下直连通道主数据表完全可行。
 * 等 pay-svc 真有了 {@code /internal/pay/pay-channels} 与配套的
 * {@code RemoteOpsPayChannelAppService}，再把条件加回来 ——
 * <b>那时是「两个实现二选一」，而不是现在的「一个实现或没有」</b>。
 *
 * <p>同类问题由 {@code PayDeploymentModePairingTest} 拦：
 * 标了 embedded 的实现必须有 standalone 的对应物。
 */
@Service
public class OpsPayChannelAppServiceImpl implements OpsPayChannelAppService {

    private final PayChannelMasterService master;
    private final PayChannelRateService rates;
    private final AuditLogPort auditLogPort;

    public OpsPayChannelAppServiceImpl(PayChannelMasterService master,
                                       PayChannelRateService rates,
                                       AuditLogPort auditLogPort) {
        this.master = master;
        this.rates = rates;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<ChannelVO> channels() {
        long now = System.currentTimeMillis();
        return master.all().stream().map(c -> toVO(c, now)).toList();
    }

    @Override
    public ChannelVO update(String channel, Boolean enabled, String markets,
                            String currency, String settleCycle) {
        SysPayChannel row = master.updateSettings(channel, enabled, markets, currency, settleCycle);
        auditLogPort.record("PAY_CHANNEL_UPDATE", channel + " enabled=" + row.getEnabled(),
                SecurityUtils.currentUserNo());
        return toVO(row, System.currentTimeMillis());
    }

    @Override
    public RateVO addRate(String channel, String payMethod, String legalForm, Integer rateBp,
                          Long minFeeMinor, Long effectiveFrom, String remark) {
        SysPayChannelRate rate = new SysPayChannelRate();
        rate.setPayChannel(channel);
        rate.setPayMethod(payMethod);
        rate.setLegalForm(legalForm);
        rate.setRateBp(rateBp);
        rate.setMinFeeMinor(minFeeMinor == null ? 0L : minFeeMinor);
        rate.setEffectiveFrom(effectiveFrom == null ? System.currentTimeMillis() : effectiveFrom);
        rate.setRemark(remark);
        SysPayChannelRate saved = rates.add(rate);
        auditLogPort.record("PAY_CHANNEL_RATE_ADD",
                channel + " " + saved.getPayMethod() + "/" + saved.getLegalForm()
                        + "=" + saved.getRateBp() + "bp", SecurityUtils.currentUserNo());
        return toRateVO(saved);
    }

    private ChannelVO toVO(SysPayChannel c, long at) {
        var eff = rates.effective(c.getPayChannel(), SysPayChannelRate.ANY, SysPayChannelRate.ANY, at);
        /*
         * currentRate 用「生效版本」的三个数补齐成 RateVO：effective 只返回
         * rateBp/minFeeMinor/rateNo，其余字段从 history 里那一条取。
         * 找不到就是 null —— **页面要显示「未配置费率」而不是 0**。
         */
        List<RateVO> history = rates.history(c.getPayChannel()).stream()
                .map(OpsPayChannelAppServiceImpl::toRateVO).toList();
        RateVO current = eff == null ? null : history.stream()
                .filter(r -> r.rateNo() != null && r.rateNo().equals(eff.rateNo()))
                .findFirst()
                .orElse(new RateVO(eff.rateNo(), c.getPayChannel(), null, null,
                        eff.rateBp(), eff.minFeeMinor(), null, true, null));
        /*
         * markets 从关系表派生回 JSON 数组字面量 —— **ops-web 的契约不变**。
         * 换存储不该让调用方跟着改；而这里也不能改读那一列，
         * 读了就会出现「运营改了、页面显示旧值」。
         */
        List<String> ms = master.marketsOf(c.getPayChannel());
        String marketsJson = ms.isEmpty() ? null
                : ms.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"));
        return new ChannelVO(c.getPayChannel(), c.getName(), Boolean.TRUE.equals(c.getEnabled()),
                marketsJson, c.getCurrency(), c.getSettleCycle(),
                Boolean.TRUE.equals(c.getSupportsSubsidy()), current, history);
    }

    private static RateVO toRateVO(SysPayChannelRate r) {
        return new RateVO(r.getRateNo(), r.getPayChannel(), r.getPayMethod(), r.getLegalForm(),
                r.getRateBp(), r.getMinFeeMinor(), r.getEffectiveFrom(), r.getEnabled(),
                r.getRemark());
    }
}
