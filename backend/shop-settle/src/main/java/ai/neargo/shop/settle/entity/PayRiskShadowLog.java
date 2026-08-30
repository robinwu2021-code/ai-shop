package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 资金风控 · 影子期日志。
 *
 * <p><b>影子期专用</b>：跑完复盘、定了阈值之后这张表可以删。
 * 不留的话复盘无从做起，而复盘正是影子模式的全部目的。
 */
@Getter
@Setter
@TableName("pay_risk_shadow_log")
public class PayRiskShadowLog extends BaseEntity {

    private String logNo;
    private String entityNo;
    private String batchNo;
    private String verdict;

    /** 命中了哪几条规则，逗号分隔。**要能看出是哪一条** —— 否则复盘时不知道该调哪个阈值 */
    private String hitRules;

    /** 会展示给商家的原话。影子期不展示，但要记 —— 复盘时要判断「这句话说得清不清楚」 */
    private String explainText;

    /** 如果真拦，会拦住多少钱。**复盘的第一个数** */
    private Long wouldHoldMinor;

    /** 退款率（万分比）。<b>-1 = 分母为零，不出结论</b> */
    private Integer refundRateBp;

    private Long debtMinor;
    private Long depositMinor;
}
