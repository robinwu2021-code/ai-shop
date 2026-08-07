package ai.neargo.shop.marketing.attribution;

import ai.neargo.shop.marketing.attribution.dto.AttributionVO;

/**
 * 归因引擎（[API 清单 §2.9] / P-9.1）。ADR-004 之后，归因的主战场从「谁邀请的」
 * 变成「从哪家店进来的」—— 因为它直接决定费率档（R16）。
 */
public interface AttributionService {

    /**
     * 上报一次归因线索并按优先级裁决。
     *
     * <p>规则（B1 口径，全局唯一）：
     * <ol>
     *   <li>优先级 {@code STORE_CODE > INVITER > CHANNEL}</li>
     *   <li>当前归属**未过期**且来源更强 → 保持（KEPT）</li>
     *   <li>来源相同或更强 → 覆盖（REPLACED）—— 用户用脚投票，不该先到先得</li>
     *   <li>过期 → 任何来源都可重建（CREATED）</li>
     * </ol>
     * 每次判定都留痕，无论是否改变归属。
     */
    AttributionVO report(String userNo, Clue clue);

    /** 当前归属；不存在或已过期返回 null。 */
    AttributionVO current(String userNo);

    record Clue(String merchantNo, String inviterNo, String channel) {
    }
}
