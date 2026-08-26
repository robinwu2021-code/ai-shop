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
     * 库存健康度：负库存 / 零库存仍在架 / 长期未动销。
     *
     * <p>下钻到商家 → SKU。**这些商品正在给买家制造失败的下单** ——
     * 一个下不了单的商品比没有这个商品更贵：那次点击是花钱买来的。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/inventory/health")
    public List<BalanceVO> health(@RequestParam String entityNo,
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
        return query.ledger(acl.ownerIdOf(entityNo), itemId, null, cursor, Math.min(size, PAGE_MAX));
    }
}
