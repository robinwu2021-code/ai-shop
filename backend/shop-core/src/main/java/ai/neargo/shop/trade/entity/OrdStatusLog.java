package ai.neargo.shop.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 订单状态时间线（C-OC-03 / C5）。
 *
 * <p><b>append-only，不继承 {@code BaseEntity}</b>：没有 {@code version} 与 {@code deleted} ——
 * 状态变更是既成事实，能改历史等于能伪造凭证。与 {@code sys_outbox} 同类。
 *
 * <p>{@code operatorType}/{@code operatorNo} 不是可选的：客服代客操作（M6 权限边界）
 * 必须能回答「这一步是谁点的」。
 */
@Getter
@Setter
@TableName("ord_status_log")
public class OrdStatusLog {

    public static final String BY_USER = "USER";
    public static final String BY_MERCHANT = "MERCHANT";
    public static final String BY_PLATFORM = "PLATFORM";
    public static final String BY_SYSTEM = "SYSTEM";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 时间线锚点：订单 / 售后 / 发货单流水挂所属子单；支付单流水挂支付单号 */
    private String subOrderNo;
    /** 聚合：PAYMENT / ORDER / SHIPMENT / AFTER_SALE（V402；存量为 ORDER） */
    private String aggregateType;
    /** 聚合单号（V402；存量为空 = 同 subOrderNo） */
    private String aggregateNo;
    /** 状态机事件名（V402；存量为空） */
    private String event;
    /** 迁移前状态（V402；创建迁移与存量为空） */
    private String fromStatus;
    private String status;
    private String label;
    private String operatorType;
    private String operatorNo;
    private Long at;

    private String tenantNo;
    private LocalDateTime createdAt;
}
