package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 库存余额：available = on_hand - reserved，派生不落库 */
@Getter
@Setter
@TableName("inv_stock_balance")
public class InvStockBalance extends InvMutableEntity {

    private String ownerId;

    private String itemId;

    /** ★ 不可空。主体级库存 = 一个 is_default 的库位，**一种表达而不是两种** */
    private String locationId;

    /** 实存。只有单据过账能改；不允许为负 —— 让错误停在录入处，而不是流进报表 */
    private Integer onHand;

    /** 已预留未出库。只有预留能改 */
    private Integer reserved;

    /** 本库位阈值覆盖；空 = 用 inv_item.safety_stock。城西店与仓库的安全线不可能一样 */
    private Integer safetyStock;

    /** 最近一次变动。滞销判定直接读它，不必扫流水 */
    private LocalDateTime lastMovedAt;

    /** 乐观锁 */
    private Long version;

}
