package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 盘点单：盘点自己不改余额，盈亏各生成一张单 */
@Getter
@Setter
@TableName("inv_stock_count")
public class InvStockCount extends InvMutableEntity {

    private String countNo;

    private String ownerId;

    private String locationId;

    /** ALL 全店 / CATEGORY 按类 / SELECTED 指定 */
    private String scope;

    /** DRAFT / COUNTING / POSTED / VOIDED */
    private String status;

    /** 锁账面数的那一刻 */
    private LocalDateTime startedAt;

    private LocalDateTime postedAt;

    /** 过账时生成的盘盈入库单 */
    private String gainInboundNo;

    /** 过账时生成的盘亏出库单 */
    private String lossOutboundNo;

    private String operator;

    private String remark;

}
