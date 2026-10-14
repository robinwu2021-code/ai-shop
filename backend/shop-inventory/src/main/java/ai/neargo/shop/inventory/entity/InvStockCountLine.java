package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 盘点单行 */
@Getter
@Setter
@TableName("inv_stock_count_line")
public class InvStockCountLine extends InvMutableEntity {

    private String countNo;

    private Integer lineNo;

    private String ownerId;

    private String itemId;

    /** ★ 开始盘点那一刻的账面数**快照**。不快照的话，从开盘到过账之间正常卖掉的量会被算成盘亏 */
    private Integer bookQty;

    /** 实盘数。未录时为空 */
    private Integer countedQty;

    /** = counted_qty - book_qty。落库便于导出，diff=0 的行不生成任何单据 */
    private Integer diffQty;

    /** 差异原因 */
    private String reasonCode;

}
