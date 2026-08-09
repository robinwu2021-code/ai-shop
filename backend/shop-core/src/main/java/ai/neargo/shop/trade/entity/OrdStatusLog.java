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

    private String subOrderNo;
    private String status;
    private String label;
    private String operatorType;
    private String operatorNo;
    private Long at;

    private String tenantNo;
    private LocalDateTime createdAt;
}
