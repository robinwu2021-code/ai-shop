package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Restaurant fulfillment projection; financial and inventory documents retain their original
 * owners.
 */
@Getter
@Setter
@TableName("fnb_service_event")
public class FnbServiceEvent extends BaseEntity {
    private String entityNo;
    private String eventNo;
    private String taskNo;
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
