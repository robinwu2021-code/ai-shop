package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业主写入方翻转与对账（TDD §2.3 过渡、M8 T8.5、AC-27）。
 *
 * <p>翻转分三步：先置 {@code FLIPPING}（两边都拒写）→ 等在途写入落地 → 对账；
 * 账平才翻到目标，不平就翻回原写入方并把差异交给人。翻回（{@code SVC → EMBEDDED}）走同一条路，就是回滚。
 */
public interface WriteAuthorityFlipService {

    /** 只对账、不翻转：余额与流水、逐件在仓与余额。 */
    Reconciliation reconcile(String ownerId);

    /**
     * @param to 目标写入方 {@code EMBEDDED} / {@code SVC}
     * @return 对账结果；{@code clean = false} 时写入方已恢复为原值
     * @throws ai.neargo.inventory.api.InventoryException {@code CONFLICT} 已在翻转中
     */
    Result flip(String ownerId, String to, String operator);

    record Result(String from, String to, String writeAuthority, Reconciliation reconciliation) {
    }

    record Reconciliation(boolean clean, int balances, List<Diff> diffs) {
    }

    /**
     * @param kind {@code LEDGER}：余额在手 ≠ 流水之和；{@code SERIAL}：逐件物料在仓件数 ≠ 余额在手
     */
    record Diff(String kind, String itemId, String locationId, BigDecimal book, BigDecimal expected) {
    }
}
