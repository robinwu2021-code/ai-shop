package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvLedger;

import java.util.List;

/**
 * SN 查询与拆装（TDD §2.6、§2.11，M3 T3.3 / T3.4）。
 *
 * <p>组装只有一层（柜 ⊃ 宝）：上级件自己不能再挂在别人下面，下级件自己不能再有下级 ——
 * 再深一层，「扫一个码带出什么」就说不清了。拆装不改余额（件还在同一个仓），只记审计。
 */
public interface SerialService {

    void assemble(Caller caller, String childSerialNo, String parentSerialNo);

    void disassemble(Caller caller, String childSerialNo);

    /** 在仓的 SN（只返回 OPEN 周期）。参数为空 = 不按它过滤。 */
    List<InStock> inStock(String ownerId, String itemId, String locationId, String parentSerialNo);

    /** 流水（只追加）：按游标往后翻，{@code afterId} 为空从头开始，一次最多 500 行。 */
    List<InvLedger> ledger(String ownerId, String itemId, String locationId, String docNo, Long afterId, int limit);

    record InStock(String serialNo, String itemId, String locationId, String parentSerialNo, String episodeId,
                   String gateRef, List<String> holdReasons) {
    }
}
