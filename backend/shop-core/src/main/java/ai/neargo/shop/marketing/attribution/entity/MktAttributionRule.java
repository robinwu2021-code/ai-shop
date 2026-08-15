package ai.neargo.shop.marketing.attribution.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 归因规则（P-9.1，单行配置，V121）。
 *
 * <p><b>它必须真的驱动归因引擎</b>：优先级此前写死在 {@link MktAttribution#weightOf}、
 * 窗口期写死在 {@code @Value("${shop.attribution.window-days:30}")} ——
 * 运营端有页面，改完什么都不会发生。只把它做成一张能存能读的表，
 * 等于把那个缺陷原样保留下来，还多了一处「看起来生效了」的假象。
 *
 * <p><b>为什么这件事重要</b>：归因结果决定订单的 {@code trafficSource}，
 * 而它直接决定商家付多少佣金（ADR-004 §6：自带客流低费率/零佣金，平台客流正常费率）。
 * 规则配错或配了不生效，商家的账单就是错的。
 */
@Getter
@Setter
@TableName("mkt_attribution_rule")
public class MktAttributionRule extends BaseEntity {

    /** 一期恒 MAIN；预留多套规则 */
    public static final String MAIN = "MAIN";

    /** 已有归属时保留先来的 */
    public static final String KEEP_FIRST = "KEEP_FIRST";
    /** 覆盖（后端既有行为，被 M6aStoreAttributionFlowTest 钉住） */
    public static final String OVERWRITE = "OVERWRITE";
    /** 问用户 —— 一期不实现，存得下但引擎按 OVERWRITE 处理，见 AttributionServiceImpl */
    public static final String ASK_USER = "ASK_USER";

    private String ruleKey;

    /** 归因优先级，高→低，逗号分隔。**全序、不重不漏** —— 半个优先级表在冲突时会随机裁决 */
    private String priority;

    /** 归因窗口期（天），1–90。0 等于关掉归因，故不允许 */
    private Integer windowDays;

    /** {@link #KEEP_FIRST} / {@link #OVERWRITE} / {@link #ASK_USER} */
    private String conflictPolicy;

    /** 新客判定因子，逗号分隔：DEVICE / PHONE。一个都不选 = 所有人都是新客，新人券会被无限领 */
    private String newUserFactors;
}
