package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 账期批次：<b>一个主体、一个通道、一个账期，一批</b>。
 *
 * <p><b>批次管「能不能放」，单据管「放得成不成」。</b>
 * 两件事不塞进 {@link StlBill#getStatus()} 里 —— 混进去之后那一列会同时表达
 * 「钱在哪」与「流程走到哪」，而这两个问题的答案本来就可以独立变化。
 *
 * <p>为什么必须有这个对象：没有它，「这个账期对完了没有」无处安放，
 * 「这一单卡在哪一批」也查不出来。今天结算单生成之后就没有任何东西推动它 ——
 * 而推动它的前提，正是先有一个「这一批可以放了」的判断。
 *
 * <p>方案见 {@code docs/technical/design/账期与对账放款-方案.md}。
 */
@Getter
@Setter
@TableName("stl_settle_batch")
public class StlSettleBatch extends BaseEntity {

    /** 开批，正在收单。可结算的单不断进来 */
    public static final String DRAFT = "DRAFT";

    /** T3 到达，截批。本批不再接新单 */
    public static final String COLLECTED = "COLLECTED";

    /** 三道对账门在跑 */
    public static final String RECONCILING = "RECONCILING";

    /**
     * 有未处置差异或风控命中，<b>整批</b>挂起。
     *
     * <p>挂起必须带 {@link #getBlockExpireAt()} —— 没有时限的挂起等于永久冻结，
     * 而它会以「还在排查」的形式一直存在，不会有任何人觉得不对。
     */
    public static final String BLOCKED = "BLOCKED";

    /** 三道门全过，可放行 */
    public static final String RECONCILED = "RECONCILED";

    /** 已逐单下发指令。之后每张单各走各的状态机 */
    public static final String RELEASED = "RELEASED";

    /**
     * 对账覆盖面：<b>仅我方自查</b>。
     *
     * <p>过渡期只有 A 侧（通道账单下载能力还没有），界面据此如实标注。
     * 没有 B 侧时说「已对账」是一句自证的话 —— A 侧只能回答
     * 「我方内部一致吗」，回答不了「对方到底做了什么」。
     */
    public static final String SCOPE_SELF_ONLY = "SELF_ONLY";

    /** 含对方账单比对。接上通道账单下载之后才可能是它 */
    public static final String SCOPE_BOTH = "BOTH";

    /** 超时自动放行的操作者标识。**要能单独统计** —— 这个数持续大于零说明挂起时限比处置能力短 */
    public static final String SYSTEM_TIMEOUT = "SYSTEM_TIMEOUT";

    private String batchNo;

    /** 主体业务键。<b>跨库引用只认业务键，不认自增 id</b> */
    private String entityNo;

    /** 一个主体在不同通道各自成批：账期与费率都按通道走 */
    private String payChannel;

    /** 本批采用的账期规则<b>快照</b>。配置会变，历史账不能跟着变 */
    private String settleCycle;

    /** 收单区间起（含），毫秒 */
    private Long periodFrom;

    /** 收单区间止（不含），毫秒 */
    private Long periodTo;

    /** T3 应结日 */
    private Long dueAt;

    /** 实际放行时刻。与 {@link #dueAt} 分开才答得出「晚了几天、晚在哪一段」 */
    private Long releasedAt;

    /**
     * Tmax：通道冻结窗口到期时刻。
     *
     * <p>取本批<b>最早一单</b>的成交时刻 + 冻结窗口。取平均或取最晚都会让告警
     * 晚于实际到期 —— 整批一起放，而最早的那一笔先到期，
     * <b>它到期就意味着这一批已经出问题了</b>。
     */
    private Long freezeExpireAt;

    private String status;

    private Integer billCount;

    /** 本批结算基数合计（分） */
    private Long grossMinor;

    /** 本批应放款合计（分） */
    private Long netMinor;

    private String reconScope;

    /**
     * 挂起原因。<b>直接展示给商家的原话</b>，必须含具体数字与阈值
     * （「近 7 天退款率 32%，阈值 20%」），不能是「风控审核中」——
     * 说不出是哪一笔的提示，商家读完还是要找客服。
     */
    private String blockedReason;

    private Long blockedAt;

    /** 挂起时限。超时自动放行并告警 */
    private Long blockExpireAt;

    /** 人工放行者；超时放行写 {@link #SYSTEM_TIMEOUT} */
    private String decidedBy;

    /** 人工放行/继续挂起<b>都必须写原因</b> */
    private String decideRemark;

    /**
     * 记账币种（V287）。<b>决定这个金额能不能与别的相加。</b>
     *
     * <p>补这一列与多区域无关，单币种下它也是对的 ——
     * 只是没有第二个币种时看不出错，而错的形状是
     * 「把 100 台币当成 100 人民币加进合计」，不报错、只是数字不对。
     *
     * <p><b>写入路径必须显式赋值</b>，不能靠 DEFAULT 活着：
     * 靠默认值的列在第二个币种出现时会静默地全部写成人民币。
     * {@code stl_payment.currency} 就是活例子 —— 那一列 V1 就有，
     * 而生产代码里没有任何一处给它赋值。
     */
    private String currency;
}
