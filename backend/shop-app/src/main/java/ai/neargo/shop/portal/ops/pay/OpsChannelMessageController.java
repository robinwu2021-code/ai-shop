package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.master.ChannelMessageQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道报文查询（O1）。
 *
 * <h2>为什么它值得一个端点</h2>
 * V286 起报文已经在落库，而<b>没有任何地方能看</b> —— 落了没人看的表等于没落。
 * 查掉单、查通道纠纷时第一个要翻的就是它：
 * 「通道到底推了什么过来」「我方为什么拒了」。
 * 那两个问题今天没人答得上，因为答案只在日志里，而日志按天滚。
 *
 * <h2>不进商家端</h2>
 * 商家看自己的报文没有用，而报文里有通道侧的商户号 ——
 * 那是别人的商户结构，不该出现在他的界面上。
 *
 * <h2>权限沿用对账那把</h2>
 * 查报文与查对账差异是同一件事的两面（都是「账对不上时去找原因」），
 * 而<b>新开一个权限码要在五处登记</b>，多一个码就多一处会被落下的地方。
 */
@Profile("ops")
@RestController
@Validated
public class OpsChannelMessageController {

    private final ChannelMessageQueryService messages;

    public OpsChannelMessageController(ChannelMessageQueryService messages) {
        this.messages = messages;
    }

    /**
     * 报文列表。
     *
     * <p>⚠️ <b>按单号筛会滤掉最该看的那几行</b>：验签失败时我方拿不到单号，
     * 那种行的 {@code bizNo} 是空的。所以端上默认不带单号，
     * 并在按单号筛时提示这一点。
     */
    @GetMapping("/ops/channel-messages")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RECON_READ + "')")
    public ChannelMessageQueryService.Page list(
            @RequestParam(required = false) String payChannel,
            @RequestParam(required = false) String msgType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String bizNo,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return messages.page(payChannel, msgType, outcome, bizNo, page, size);
    }

    /** 单条详情（含完整报文与请求头）。 */
    @GetMapping("/ops/channel-messages/{messageNo}")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_RECON_READ + "')")
    public ChannelMessageQueryService.MessageVO detail(@PathVariable String messageNo) {
        return messages.find(messageNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }
}
