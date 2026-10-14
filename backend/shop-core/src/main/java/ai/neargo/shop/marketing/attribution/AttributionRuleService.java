package ai.neargo.shop.marketing.attribution;

import java.util.List;

/**
 * 归因规则的读写（P-9.1.1/9.1.2/9.1.5）。
 *
 * <p><b>读的一侧是给归因引擎用的，不只是给运营页面回显</b>——
 * 这正是这张表存在的理由：规则改完必须真的改变判定，否则运营端那一页
 * 就是个「点了没反应」的摆设。
 */
public interface AttributionRuleService {

    /** 当前生效规则。**永远有值**：库里没有行时返回默认规则（与 DDL 默认值一致）。 */
    RuleVO current();

    /**
     * 保存规则。四项都要校验，因为它们各自有一种「存下去之后静默出错」的方式：
     * <ul>
     *   <li>优先级必须是三个来源的**全序**——少一个，那种来源在冲突时无从裁决</li>
     *   <li>窗口期 1–90——0 等于悄悄关掉归因，全平台订单变成平台客流，商家佣金翻倍</li>
     *   <li>冲突策略必须是三个枚举之一</li>
     *   <li>新客因子非空——一个都不选等于所有人都是新客，新人券会被无限领</li>
     * </ul>
     */
    RuleVO save(SaveCommand cmd, String operatorNo);

    /**
     * 字段与 ops-web 契约 {@code AttributionRule} 一一对应。
     *
     * @param priority       高→低的全序，如 {@code [STORE_CODE, INVITER, CHANNEL]}
     * @param newUserFactors {@code DEVICE} / {@code PHONE}
     * @param updatedAt      ISO 时间。**要下发** —— 运营改了费率相关的规则，
     *                       下一个人打开这一页必须看得到「谁在什么时候改的」
     */
    record RuleVO(List<String> priority, int windowDays,
                  String conflictPolicy, List<String> newUserFactors,
                  String updatedAt, String updatedBy) {

        /** 引擎用的优先级列表（与 {@link #priority()} 同一份，命名保留可读性）。 */
        public List<String> priorityList() {
            return priority();
        }
    }

    record SaveCommand(List<String> priority, Integer windowDays, String conflictPolicy,
                       List<String> newUserFactors) {
    }
}
