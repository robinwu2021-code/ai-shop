package ai.neargo.shop.inventory.api.ops;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 进销存治理。
 *
 * <p><b>一个写端点都没有，这是有意的。</b>运营能改商家库存的那一刻，
 * 「这个数是谁改的」就多了一个答案，而商家不会知道。
 * 要改只能让商家自己改，或走工单留痕。
 *
 * <p>权限用 {@code product:sku:read}（读码）而不是 {@code product:stock:update}：
 * 只读页挂写码，等于**只有能改的人才看得到账** —— 而看账的是运营与客服。
 */
@Profile("ops")
@ConditionalOnInventory
@RestController
public class OpsInventoryController {

    private static final int PAGE_MAX = 200;

    private final StockQueryService query;
    private final InventoryAclService acl;

    public OpsInventoryController(StockQueryService query, InventoryAclService acl) {
        this.query = query;
        this.acl = acl;
    }

    /**
     * <b>某一个商家</b>的库存余额（默认只给有待办标记的：缺货 / 滞销）。
     *
     * <p><b>它一度叫 {@code /ops/inventory/health}，那个名字是错的</b>：
     * 它必须先知道看哪个商家（{@code entityNo} 必填），而运营要的「健康度」是
     * <b>不知道该看谁</b>时的那一屏 —— 平台上此刻有哪些商家的库存正在制造失败的下单。
     * 两件事共用一个名字的代价是运营端照着名字接了过来，拿到的却是 400。
     * 平台级那一个在 {@code shop-app} 的 {@code OpsInventoryHealthController}：
     * 它要同时读平台侧的「在架」与本域的余额，而<b>本域不认识平台的表</b>。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/inventory/balances")
    public List<BalanceVO> balances(@RequestParam String entityNo,
                                  @RequestParam(defaultValue = "todo") String type,
                                  @RequestParam(defaultValue = "100") int size) {
        return query.balances(acl.ownerIdOf(entityNo), null, type, Math.min(size, PAGE_MAX));
    }

    /** 商家台账（**只读**）。客服回答「我的货怎么少了」时有据可查。 */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/inventory/ledger")
    public LedgerPageVO ledger(@RequestParam String entityNo,
                               @RequestParam(required = false) String itemId,
                               @RequestParam(required = false) Long cursor,
                               @RequestParam(defaultValue = "50") int size) {
        return query.ledger(acl.ownerIdOf(entityNo), itemId, null, null, cursor, Math.min(size, PAGE_MAX));
    }
}
