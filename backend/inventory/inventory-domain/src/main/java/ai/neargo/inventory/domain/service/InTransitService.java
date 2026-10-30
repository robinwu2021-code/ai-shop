package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「货在外面」的统一视图（TDD §2.20，评审 A3）：出库后还没到对方手里的三种状态合在一处查。
 *
 * <ul>
 *   <li>{@code POOL}：出库池待签收（交接方式 POOL），按顶层目标 + 物料汇总；</li>
 *   <li>{@code PAIRED}：成对单据已发未收（交接方式 RECEIPT），目标是收货方账本；</li>
 *   <li>{@code TRANSFER}：账内调拨已发运未收货，目标是调入库位。</li>
 * </ul>
 * 只读，不另存：三种状态各自的真源不变。
 */
public interface InTransitService {

    List<InTransit> of(String ownerId);

    /**
     * @param rootTargetType POOL：顶层目标类型；PAIRED：收货方账本的宿主类型；TRANSFER：{@code LOCATION}
     * @param docNo          POOL 汇总行为空；PAIRED 为出库单号；TRANSFER 为调拨单号
     */
    record InTransit(String kind, String rootTargetType, String rootTargetRef, String itemId, BigDecimal qty,
                     String docNo) {
    }
}
