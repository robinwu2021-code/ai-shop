package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 厨房事件：厨房动作的事实（TDD-餐饮域迁移新库 §2.8，服务事件并入）。财务与库存单据仍归各自的归属模块，这里只记凭据号。
 */
@Getter
@Setter
@TableName("fnb_kitchen_event")
public class FnbKitchenEvent extends BaseEntity {
    private String entityNo;
    private String eventNo;
    private String taskNo;
    /** 动作类型，列名 {@code event_type}。 */
    @TableField("event_type")
    private String action;
    private BigDecimal qty;

    /** Portion discarded after READY; remaining LOSS quantity was discarded during production. */
    private BigDecimal readyLossQty;

    private String operationKey;
    private String sourceRequestHash;
    private String reason;
    private String inventoryDocNo;
    private String custodySnapshot;
}
