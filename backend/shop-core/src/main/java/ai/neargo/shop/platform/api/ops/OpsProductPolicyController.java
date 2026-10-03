package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.ProductPolicyPort;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 建品规则（商品①）。
 *
 * <h2>默认值一律等于「今天的行为」</h2>
 *
 * {@code requireCover=false}、两个长度都是 0（不限）。<b>这不是偷懒。</b>
 * 这三条规则一旦生效，命中的存量商品下次提审全会被拦 ——
 * 而平台上有 200 个 SPU、194 个正卡在审核里。默认打开等于在没人预告的情况下
 * 让一批商家的提交突然失败，而他们只会看到一个自己没做错什么的报错。
 *
 * <p>要开就该是有人看过数、知道会拦下多少之后主动开的。
 * 所以默认关，界面上也说清楚「开之前先看看会拦下多少」。
 */
@Profile("ops")
@RestController
public class OpsProductPolicyController {

    private static final String KEY = "product.policy";
    /** 标题长度的上限的上限。再大就不是规范而是没规范 */
    private static final int LEN_MAX = 200;

    private final SettingPort settings;
    private final AuditLogPort auditLog;

    public OpsProductPolicyController(SettingPort settings, AuditLogPort auditLog) {
        this.settings = settings;
        this.auditLog = auditLog;
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    @GetMapping("/ops/product/policy")
    public ProductPolicyPort.Policy policy() {
        return ProductPolicyPort.parse(settings.get(KEY, null));
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @PostMapping("/ops/product/policy")
    public ProductPolicyPort.Policy save(@RequestBody ProductPolicyPort.Policy req) {
        int min = req.titleMinLength();
        int max = req.titleMaxLength();
        if (min < 0 || max < 0 || min > LEN_MAX || max > LEN_MAX) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 下限比上限还大的话，**任何标题都提交不了**，而报错会说「标题太短」
        if (min > 0 && max > 0 && min > max) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        settings.put(KEY, "{\"requireCover\":" + req.requireCover()
                + ",\"titleMinLength\":" + min + ",\"titleMaxLength\":" + max + "}",
                SecurityUtils.currentUserNo());
        // 这三条决定「谁提交不上来」，三个月后要能查是谁开的
        auditLog.record("PRODUCT_POLICY", KEY,
                "必填主图=" + req.requireCover() + "｜标题 " + min + "–" + max, true);
        return new ProductPolicyPort.Policy(req.requireCover(), min, max);
    }
}
