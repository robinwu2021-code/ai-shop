package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.channel.master.PayChannelRateService;
import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌实现。独立形态的远程实现待 M1 之后单独做 —— 那时它与费率、发票同形状 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
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
        return new ChannelVO(c.getPayChannel(), c.getName(), Boolean.TRUE.equals(c.getEnabled()),
                c.getMarkets(), c.getCurrency(), c.getSettleCycle(),
                Boolean.TRUE.equals(c.getSupportsSubsidy()), current, history);
    }

    private static RateVO toRateVO(SysPayChannelRate r) {
        return new RateVO(r.getRateNo(), r.getPayChannel(), r.getPayMethod(), r.getLegalForm(),
                r.getRateBp(), r.getMinFeeMinor(), r.getEffectiveFrom(), r.getEnabled(),
                r.getRemark());
    }
}
