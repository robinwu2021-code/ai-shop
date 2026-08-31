package ai.neargo.shop.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 分账指令与回执（append-only）。
 *
 * <p>{@code requestNo} 唯一 —— 平台侧幂等号，与支付服务商的幂等号构成双保险：
 * 重复执行分账在这一层就会被挡下，不必依赖对方的幂等实现是否可靠。
 */
@Getter
@Setter
@TableName("stl_split_log")
public class StlSplitLog {

    public static final String SPLIT = "SPLIT";
    public static final String REVERSE = "REVERSE";
    /** 积分补差：平台把买家用积分抵掉的那部分补进二级商户，让商家按全额收款。 */
    public static final String SUBSIDY = "SUBSIDY";
    /** 补差回退：退款时把补贴部分收回平台。 */
    public static final String SUBSIDY_RETURN = "SUBSIDY_RETURN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settleNo;
    private String subOrderNo;
    /** SPLIT / REVERSE。列名为 split_action —— action 是 H2 保留字。 */
    private String splitAction;
    private Long amountMinor;
    private String requestNo;
    private String result;
    private String providerNo;
    private String message;
    private Long at;
    private String tenantNo;
    private LocalDateTime createdAt;
}
