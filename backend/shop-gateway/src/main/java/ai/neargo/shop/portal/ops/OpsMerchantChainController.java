package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.invbridge.MerchantChainService;
import ai.neargo.shop.invbridge.MerchantChainService.ChainRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 商家链条画像。
 *
 * <p>一家一行：建品 → 提审 → 上架 → 建账 → 首次进货 → 持续记账。
 * 这一页回答的是别处答不上的那个问题：<b>上一步到这一步掉了多少、掉在谁身上</b>。
 *
 * <p><b>只有读。</b>动作在别的端点上（M2）—— 一个既统计又处置的端点，
 * 出问题时分不清是数错了还是做错了。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsMerchantChainController {

    /** 一次最多返回多少行 */
    private static final int LIMIT_MAX = 500;

    private final MerchantChainService chain;

    public OpsMerchantChainController(MerchantChainService chain) {
        this.chain = chain;
    }

    /**
     * @param stuckOnly 只要卡住的。**默认 false** —— 默认只给卡住的那些行，
     *                  会让「今天没人卡住」和「这一页坏了」长得一模一样
     */
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    @GetMapping("/ops/merchant/chain")
    public List<ChainRow> chain(@RequestParam(defaultValue = "200") int limit,
                                @RequestParam(defaultValue = "false") boolean stuckOnly) {
        return chain.profile(Math.min(Math.max(limit, 1), LIMIT_MAX), stuckOnly);
    }
}
