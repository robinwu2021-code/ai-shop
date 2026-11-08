package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 商家自报的一笔 App 外收款（TDD-商户App外部记账）。
 *
 * <p>只保存当前值；每一步的前后值在 {@link OrdOfflineRecordEvent}。它不是支付单：不进支付台账、
 * 不进通道对账、不能退款，带了 {@code orderNo} 也只是引用。{@code version} 即对外的 {@code version}，
 * 更正与作废时由乐观锁加一。
 */
@Getter
@Setter
@TableName("ord_offline_record")
public class OrdOfflineRecord extends BaseEntity {
    public static final String RECORDED = "RECORDED";
    public static final String VOIDED = "VOIDED";

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String recordNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String entityNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storeNo;

    private Long amountMinor;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String currency;

    private String method;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String orderNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String note;

    /** 实际收款时刻，UTC。 */
    private LocalDateTime receivedAt;

    private String status;
    private Boolean corrected;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String recordedBy;

    /** SHA-256(Idempotency-Key)；原文不落库。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String idemKeyHash;

    /** 首次请求体摘要，判「同键不同体」。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String requestHash;
}
