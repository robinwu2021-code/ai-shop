package ai.neargo.shop.fulfillment.dto;

/**
 * 逾期处置规则（P-5.1.4）。存在 {@code sys_setting} 的一行 JSON 里。
 *
 * @param action      POSTPONE=顺延 / VOID=作废
 * @param graceHours  宽限小时数。<b>到点即作废必产生客诉</b>，所以 VOID 也必须留宽限期（≥1）。
 *                    默认 24 —— 取自矩阵 §七之二 {@code pickupGraceDays}（1 天）的等价小时数
 * @param maxPostpone 顺延次数上限（action=POSTPONE 时有意义）。至少 1，
 *                    否则「顺延 0 次」名为顺延实为作废
 * @param updatedAt   最后修改时间（ISO-8601）。改规则会改变看板上的逾期数，留痕不是可选项
 * @param updatedBy   最后修改人（运营 staffNo）
 */
public record OverdueRuleVO(String action, int graceHours, int maxPostpone,
                            String updatedAt, String updatedBy) {

    public static final String POSTPONE = "POSTPONE";
    public static final String VOID = "VOID";
}
