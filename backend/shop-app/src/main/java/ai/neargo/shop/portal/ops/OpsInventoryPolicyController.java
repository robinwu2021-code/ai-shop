package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 进销存平台规则（M7）。
 *
 * <h2>今天只有一条，这不是没做完</h2>
 *
 * 需求里进销存① 列了六条平台规则。逐条查下来，多数不该按原样做：
 *
 * <ul>
 *   <li><b>预留 TTL 上限</b> —— 已加，但在 {@code ReservationServiceImpl} 里用配置项，
 *       不在这儿：{@code reserve} 是热路径，每次占货去查一次库不值得，
 *       而这个值一年也不会改一次。</li>
 *   <li><b>安全库存平台默认值</b> —— <b>不该做</b>。读它的地方在
 *       {@code StockQueryServiceImpl}，而 {@code shop-inventory} 是刻意隔离的模块
 *       （独立库、独立 Flyway、零 SPI 引用，为的是将来能单独交付）。
 *       让它去读平台配置，等于把那道边界拆了 —— 而换来的只是一个默认值。</li>
 *   <li><b>入库必填项</b> —— 采购入库<b>本来就在拦成本价</b>，不是没规则。</li>
 *   <li><b>单位字典</b> —— {@code inv_uom} 建了表而<b>一处读它的代码都没有</b>。
 *       给一张没人读的表做维护页，是把「没接上」这件事盖住。</li>
 * </ul>
 *
 * <p>留下的是真正需要人来定、而且今天就有读者的那一条。
 */
@Profile("ops")
@RestController
public class OpsInventoryPolicyController {

    /** 设置键。整条规则存成一个 JSON，加第二条规则时不用再开一个键 */
    private static final String KEY = "inventory.policy";

    /**
     * 默认要求连续 7 轮为零。
     *
     * <p>7 不是拍的：对差任务每天一轮，7 轮就是一周 ——
     * 足够覆盖「周末没单所以看起来很干净」这种假象。
     */
    private static final int DEFAULT_STREAK = 7;

    /** 上限 90：再长就不是判据而是拖延了，而拖延不需要一个配置项来支持 */
    private static final int STREAK_MAX = 90;

    private final SettingPort settings;
    private final AuditLogPort auditLog;

    public OpsInventoryPolicyController(SettingPort settings, AuditLogPort auditLog) {
        this.settings = settings;
        this.auditLog = auditLog;
    }

    @PreAuthorize("@perm.can('" + Perms.INVENTORY_STOCK_READ + "')")
    @GetMapping("/ops/inventory/policy")
    public Policy policy() {
        String json = settings.get(KEY, null);
        return new Policy(parseStreak(json));
    }

    @PreAuthorize("@perm.can('" + Perms.SYSTEM_PARAM_UPDATE + "')")
    @PostMapping("/ops/inventory/policy")
    public Policy save(@RequestBody Policy req) {
        int n = req.reconCleanStreakRequired();
        if (n < 1 || n > STREAK_MAX) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        settings.put(KEY, "{\"reconCleanStreakRequired\":" + n + "}",
                SecurityUtils.currentUserNo());
        // 这个数决定「什么时候可以切真相源」—— 三个月后要能查是谁把它改小的
        auditLog.record("INVENTORY_POLICY", KEY, "对差所需连续轮数=" + n, true);
        return new Policy(n);
    }

    /**
     * @param reconCleanStreakRequired 对差要连续几轮为零，才算够格切换真相源（G3）。
     *                                 <b>此前这个 N 根本不存在</b> —— 判据写的是
     *                                 「连续为零」，而连续多少是空的，
     *                                 于是「够了没有」这个问题谁都答不了。
     */
    public record Policy(int reconCleanStreakRequired) {
    }

    /** 存的是一个小 JSON。解析不出就用默认值 —— 配置坏了不该让页面打不开 */
    private static int parseStreak(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_STREAK;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"reconCleanStreakRequired\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) {
            return DEFAULT_STREAK;
        }
        int n = Integer.parseInt(m.group(1));
        return n >= 1 && n <= STREAK_MAX ? n : DEFAULT_STREAK;
    }
}
