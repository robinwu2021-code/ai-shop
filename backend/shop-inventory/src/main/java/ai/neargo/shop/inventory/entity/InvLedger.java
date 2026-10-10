package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库存流水：只追加，不修改，不删除
 *
 * <p><b>只追加</b>：继承 {@link InvEntity} 而不是 {@link InvMutableEntity} ——
 * 实体上没有 updatedAt，「改一行」在编译期就不成立。
 */
@Getter
@Setter
@TableName("inv_ledger")
public class InvLedger extends InvEntity {

    private String ownerId;

    private String itemId;

    private String locationId;

    /** IN / OUT */
    private String docKind;

    /** 入库单号或出库单号 */
    private String docNo;

    private Integer lineNo;

    /** 单据 source_type / purpose 的**快照**：报表按它分组免 join。它不是真源，与单据不一致时以单据为准 */
    private String reasonCode;

    /** 带符号：入为正，出为负。不拆成 in/out 两列 —— 两列会把求和变成减法，漏减不会被发现 */
    private Integer qtyDelta;

    /** 这一行之后的 on_hand。自校验：prev.balance_after + qty_delta 必须等于它 */
    private Integer balanceAfter;

    /** 过账那一刻的成本快照 */
    private Long unitCostMinor;

    /** 业务发生时间，跟单据走（可回填） */
    private LocalDateTime occurredAt;

    /** 谁干的。这一列是这张表存在的一半理由 */
    private String operator;

}
