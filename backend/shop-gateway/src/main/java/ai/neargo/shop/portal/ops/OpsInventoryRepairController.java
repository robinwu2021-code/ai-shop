package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.invbridge.InventoryBackfillService;
import ai.neargo.shop.invbridge.InventoryBackfillService.Report;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 手动补投影（M2）。
 *
 * <p>「库存对差」能看出有账外 SKU（{@code pending > 0}），而此前<b>看出来之后做不了任何事</b>：
 * 那些货在进销存里不存在，商家看不到、盘不着、进不了货。
 *
 * <h2>默认试算，要显式 apply 才真搬</h2>
 *
 * {@code InventoryBackfillService.run()} 早就有且有测试，缺的只是一个入口。
 * 但它被 {@code shop.inventory.backfill.dry-run}（默认 <b>true</b>）挡着 ——
 * <b>那个默认值是一个决定，不该被一个按钮悄悄绕过</b>。
 *
 * <p>所以这个端点自己带 {@code apply}：不传就是试算，返回「会搬多少条」；
 * 传了才写。两步之间隔着一次人的确认，而不是隔着一个配置项的默认值。
 *
 * <p><b>它不改任何数量</b>：搬过去的数就是平台侧那个数，落成一张 INIT 单据。
 * 运营在这里不可能凭空造出一个库存 —— 这与「运营不改商家库存」并不冲突。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsInventoryRepairController {

    /** 一次最多搬多少。**不给「全部」** —— 一次跑完看不出中途出了什么问题 */
    private static final int LIMIT_MAX = 500;

    private final InventoryBackfillService backfill;
    private final AuditLogPort auditLog;

    public OpsInventoryRepairController(InventoryBackfillService backfill, AuditLogPort auditLog) {
        this.backfill = backfill;
        this.auditLog = auditLog;
    }

    @PreAuthorize("@perm.can('" + Perms.INVENTORY_PROJECTION_REPAIR + "')")
    @PostMapping("/ops/inventory/repair-projection")
    public Report repair(@RequestBody(required = false) RepairReq req) {
        boolean apply = req != null && Boolean.TRUE.equals(req.apply());
        int limit = req == null || req.limit() == null
                ? 100 : Math.min(Math.max(req.limit(), 1), LIMIT_MAX);

        Report report = backfill.run(!apply, limit, null);
        if (apply) {
            // 只有真搬了才留痕：试算是一次查询，把它记进审计会把真正的动作淹掉
            auditLog.record("INVENTORY_PROJECTION_REPAIR", "-",
                    "搬 " + report.moved() + " 条、跳过 " + report.skipped()
                            + "、仍待搬 " + report.pending(), true);
        }
        return report;
    }

    /**
     * @param apply <b>缺省 false = 试算</b>。默认真搬的话，第一个点它的人就已经写了库
     * @param limit 一次最多搬多少，缺省 100
     */
    public record RepairReq(Boolean apply, Integer limit) {
    }
}
