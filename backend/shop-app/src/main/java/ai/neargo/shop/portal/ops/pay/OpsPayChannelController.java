package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.payclient.OpsPayChannelAppService.ChannelVO;
import ai.neargo.shop.payclient.OpsPayChannelAppService.RateVO;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 支付通道设置与费率。
 *
 * <p>路径落在 {@code /ops/settle/**} 与费率规则并列 —— 都是「配钱怎么算」，
 * 不该一个在结算、一个在系统设置。<b>路径逐字不变</b>（2026-09-01 从
 * {@code shop-core/platform/api/ops} 搬过来时保持原样，ops-web 不用改）。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPayChannelController {

    private final OpsPayChannelAppService app;

    public OpsPayChannelController(OpsPayChannelAppService app) {
        this.app = app;
    }

    /** 通道清单 + 每个通道的费率版本（含预约生效的） */
    @GetMapping("/ops/settle/pay-channels")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_READ + "')")
    public List<ChannelVO> channels() {
        return app.channels();
    }

    /**
     * 开关与结算属性。**能力位不在这里改** —— 那几个位是通道自己的事实
     * （支不支持补差、分账上限多少），不是运营的选择；改错会让积分抵扣
     * 在一个做不到补差的通道上开出来，而那是资金差错。
     */
    @PutMapping("/ops/settle/pay-channels/{channel}")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_UPDATE + "')")
    public ChannelVO update(@PathVariable String channel, @RequestBody UpdateReq req) {
        return app.update(channel, req.enabled(), req.markets(), req.currency(), req.settleCycle());
    }

    /** 加一版费率。**不改旧行。** */
    @PostMapping("/ops/settle/pay-channels/{channel}/rates")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RATE_UPDATE + "')")
    public RateVO addRate(@PathVariable String channel, @RequestBody AddRateReq req) {
        return app.addRate(channel, req.payMethod(), req.legalForm(), req.rateBp(),
                req.minFeeMinor(), req.effectiveFrom(), req.remark());
    }

    public record UpdateReq(Boolean enabled, String markets, String currency, String settleCycle) {
    }

    public record AddRateReq(String payMethod, String legalForm, Integer rateBp,
                             Long minFeeMinor, Long effectiveFrom, String remark) {
    }
}
