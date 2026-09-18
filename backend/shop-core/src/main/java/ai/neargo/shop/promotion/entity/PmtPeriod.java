package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 社区集单的一期：一个集单活动在一个截单日的全部订单（ADR-024）。
 *
 * <p><b>份数与金额不存</b>，从挂在它上面的订单现算 —— 存一份计数，
 * 迟早「总览 86、点进去 85」。这里只存状态机需要的东西。
 *
 * <pre>
 *   OPEN ── 截单（到点 / 商家提前）──┬─ 份数 ≥ 起订量（或未设）→ CONFIRMED
 *                                  └─ 份数 &lt; 起订量 → SHORT
 *   SHORT ── 商家「照常发货」→ CONFIRMED
 *   SHORT ── 商家「取消本期」或 超过 decide_deadline → CANCELLED（逐单全额退款）
 * </pre>
 */
@Getter
@Setter
@TableName("pmt_period")
public class PmtPeriod extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String SHORT = "SHORT";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";

    /** 超时自动取消时写进 decided_by 的值：与人工处理区分开，商家问「谁取消的」要答得上 */
    public static final String BY_SYSTEM = "SYSTEM";

    private String periodNo;
    private String activityNo;
    private String entityNo;
    /** YYYY-MM-DD，截单日（市场时区） */
    private String periodDate;
    /** 截单时刻；提前截单时改写 */
    private Long cutoffAt;
    /** YYYY-MM-DD，写进订单的 arrive_date */
    private String pickupDate;
    private String status;
    /** SHORT 时：过了这个点商家未处理即自动取消。进入 SHORT 那一刻写死，之后改活动不影响 */
    private Long decideDeadline;
    private String decidedBy;
    private Long decidedAt;
}
