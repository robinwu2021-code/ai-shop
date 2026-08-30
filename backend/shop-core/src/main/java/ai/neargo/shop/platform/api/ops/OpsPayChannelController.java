package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.PayChannelRateService;
import ai.neargo.shop.platform.entity.SysPayChannel;
import ai.neargo.shop.platform.entity.SysPayChannelRate;
import ai.neargo.shop.platform.mapper.PlatformMappers.PayChannelMapper;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 支付通道设置与费率。
 *
 * <p>落在 {@code /ops/settle/**} 与费率规则并列 —— 都是「配钱怎么算」，
 * 不该一个在结算、一个在系统设置。
 *
 * <p><b>两条与既有页面一致的规矩</b>：
 * <ul>
 *   <li><b>费率只增不改</b>：调费率是插一个新版本，旧版本永久保留 ——
 *       真正会被问到的是「上个月那批单当时按什么费率算的」；</li>
 *   <li><b>关掉一个通道只影响新进件与新下单</b>：已开通的商户与在途的单不受影响，
 *       与「停售套餐档位只影响新订阅」同一条产品规矩。</li>
 * </ul>
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayChannelController {

    private final PayChannelMapper channelMapper;
    private final PayChannelRateService rateService;
    private final AuditLogPort auditLogPort;

    public OpsPayChannelController(PayChannelMapper channelMapper,
                                   PayChannelRateService rateService,
                                   AuditLogPort auditLogPort) {
        this.channelMapper = channelMapper;
        this.rateService = rateService;
        this.auditLogPort = auditLogPort;
    }

    /** 通道清单 + 每个通道的费率版本（含预约生效的）。 */
    @GetMapping("/ops/settle/pay-channels")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_READ + "')")
    public List<ChannelVO> channels() {
        List<SysPayChannel> rows = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectList(Wrappers.<SysPayChannel>lambdaQuery()
                        .orderByAsc(SysPayChannel::getId)));
        long now = System.currentTimeMillis();
        return rows.stream().map(c -> new ChannelVO(
                c.getPayChannel(), c.getName(), Boolean.TRUE.equals(c.getEnabled()),
                c.getMarkets(), c.getCurrency(), c.getSettleCycle(),
                Boolean.TRUE.equals(c.getSupportsSubsidy()),
                rateService.effective(c.getPayChannel(), SysPayChannelRate.ANY,
                        SysPayChannelRate.ANY, now),
                rateService.history(c.getPayChannel()))).toList();
    }

    /**
     * 开关与结算属性。**能力位不在这里改** —— 那几个位是通道自己的事实
     * （支不支持补差、分账上限多少），不是运营的选择；改错会让积分抵扣
     * 在一个做不到补差的通道上开出来，而那是资金差错。
     */
    @PutMapping("/ops/settle/pay-channels/{channel}")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_UPDATE + "')")
    public ChannelVO update(@PathVariable String channel, @RequestBody UpdateReq req) {
        SysPayChannel row = DataScopeContext.executeWithoutScope(() ->
                channelMapper.selectOne(Wrappers.<SysPayChannel>lambdaQuery()
                        .eq(SysPayChannel::getPayChannel, channel).last("LIMIT 1")));
        if (row == null) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        if (req.markets() != null) {
            row.setMarkets(req.markets());
        }
        if (req.currency() != null) {
            row.setCurrency(req.currency());
        }
        if (req.settleCycle() != null) {
            row.setSettleCycle(req.settleCycle());
        }
        DataScopeContext.executeWithoutScope(() -> channelMapper.updateById(row));
        String operator = SecurityUtils.currentUserNo();
        auditLogPort.record("PAY_CHANNEL_UPDATE", channel + " enabled=" + row.getEnabled(), operator);
        long now = System.currentTimeMillis();
        return new ChannelVO(row.getPayChannel(), row.getName(), Boolean.TRUE.equals(row.getEnabled()),
                row.getMarkets(), row.getCurrency(), row.getSettleCycle(),
                Boolean.TRUE.equals(row.getSupportsSubsidy()),
                rateService.effective(channel, SysPayChannelRate.ANY, SysPayChannelRate.ANY, now),
                rateService.history(channel));
    }

    /** 加一版费率。**不改旧行。** */
    @PostMapping("/ops/settle/pay-channels/{channel}/rates")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_UPDATE + "')")
    public SysPayChannelRate addRate(@PathVariable String channel, @RequestBody AddRateReq req) {
        SysPayChannelRate rate = new SysPayChannelRate();
        rate.setPayChannel(channel);
        rate.setPayMethod(req.payMethod());
        rate.setLegalForm(req.legalForm());
        rate.setRateBp(req.rateBp());
        rate.setMinFeeMinor(req.minFeeMinor() == null ? 0L : req.minFeeMinor());
        rate.setEffectiveFrom(req.effectiveFrom() == null
                ? System.currentTimeMillis() : req.effectiveFrom());
        rate.setRemark(req.remark());
        SysPayChannelRate saved = rateService.add(rate);
        auditLogPort.record("PAY_CHANNEL_RATE_ADD",
                channel + " " + saved.getPayMethod() + "/" + saved.getLegalForm()
                        + "=" + saved.getRateBp() + "bp", SecurityUtils.currentUserNo());
        return saved;
    }

    /**
     * @param currentRate 此刻生效的那一版；<b>一条都没配时为 null</b> ——
     *                    页面要把它显示成「未配置费率」，不是 0
     */
    public record ChannelVO(String payChannel, String name, boolean enabled, String markets,
                            String currency, String settleCycle, boolean supportsSubsidy,
                            SysPayChannelRate currentRate, List<SysPayChannelRate> rates) {
    }

    public record UpdateReq(Boolean enabled, String markets, String currency, String settleCycle) {
    }

    /**
     * @param rateBp 包装类型：{@code null} 要能被当成缺参数报出来，
     *               而不是被 Jackson 当成 0 静默变成「零费率」
     */
    public record AddRateReq(String payMethod, String legalForm, Integer rateBp,
                             Long minFeeMinor, Long effectiveFrom, String remark) {
    }
}
