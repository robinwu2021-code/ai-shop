package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 结算单（ADR-002）。**按子单**：一个子单 = 一个商家 = 一次分账。
 *
 * <p>三个金额列各有用处，不能合并成一个 net：
 * 商家问「为什么这单只结了 46 块」时，要能拆成「基数 - 佣金 - 服务费」给他看。
 */
@Getter
@Setter
@TableName("stl_bill")
public class StlBill extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String SPLITTING = "SPLITTING";
    public static final String SPLIT = "SPLIT";
    public static final String RETRYING = "RETRYING";
    public static final String MANUAL = "MANUAL";
    public static final String REVERSED = "REVERSED";

    private String settleNo;
    private String subOrderNo;
    private String orderNo;
    private String merchantNo;

    /** 应结基数 = 用户实付 + **平台补贴的优惠**（平台券的钱最终要给商家）。 */
    private Long grossMinor;

    private Long commissionMinor;
    private Long serviceFeeMinor;

    /** 商家实得 = 基数 - 佣金 - 服务费。 */
    private Long netMinor;

    private String trafficSource;

    /** 万分比，**落库快照** —— 费率会变，历史账不能跟着变。 */
    private Integer commissionRate;

    private String status;
    private Long splitAt;
    private Integer retryCount;
    private String lastError;
}
