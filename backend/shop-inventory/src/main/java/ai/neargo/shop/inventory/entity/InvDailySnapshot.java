package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 日快照：派生数据，删光重跑即可 */
@Getter
@Setter
@TableName("inv_daily_snapshot")
public class InvDailySnapshot extends InvMutableEntity {

    private String ownerId;

    /** 统计日。按业务发生时间归期，不按落库时间 */
    private LocalDate statDate;

    private String itemId;

    private String locationId;

    /** 期初 = 前一天的期末 */
    private Integer openingQty;

    /** 当日入库合计 */
    private Integer inboundQty;

    /** 当日出库合计（正数） */
    private Integer outboundQty;

    /** 其中销售出库的量。动销榜取它 */
    private Integer soldQty;

    /** 销货成本。**不是销售额** —— 售价在销售域 */
    private Long soldCostMinor;

    /** 期末结存 */
    private Integer closingQty;

}
