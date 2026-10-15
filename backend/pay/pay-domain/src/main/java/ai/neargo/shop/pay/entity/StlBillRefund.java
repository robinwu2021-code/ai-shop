package ai.neargo.shop.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 结算单退款流水（append-only，V405）：一张售后单在结算单上扣了 / 回退了多少。
 *
 * <p>{@code afterSaleNo} 唯一 —— 同一张售后单重放（续跑任务、重复事件）只落一次（集群规则 R3）；
 * 同时是排查「部分退款回退了多少分账」的唯一出处。
 */
@Getter
@Setter
@TableName("stl_bill_refund")
public class StlBillRefund {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settleNo;
    private String subOrderNo;
    private String afterSaleNo;
    private Long goodsMinor;
    private Long freightMinor;
    /** 商家所得回退额（分账后 = 发给通道的金额；分账前 = 结算单商家实得扣减额） */
    private Long merchantReverseMinor;
    private Long merchantGoodsMinor;
    private Long grossCutMinor;
    private Long commissionCutMinor;
    private Long serviceFeeCutMinor;
    private Long pointsFeeCutMinor;
    private Long pointsCostCutMinor;
    private Long freightReverseMinor;
    private String cashAllocation;
    private Long subsidyReturnMinor;
    /** 平台少收（佣金 + 服务费 + 积分服务费） */
    private Long platformCutMinor;
    /** true = 分账前（只扣金额）；false = 分账后（经通道回退） */
    private Boolean preSplit;
    private Boolean finalRefund;
    private Long at;
    private String tenantNo;
    private LocalDateTime createdAt;
}
