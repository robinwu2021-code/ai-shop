package ai.neargo.shop.settle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 账期规则：从「这一单什么时候可结算」（T2）推出「哪一天该放款」（T3）。
 *
 * <p>做成纯函数而不是服务：它没有任何外部依赖，而它算错的表现是
 * <b>钱晚到或早到几天</b> —— 这种错要能用一个不连库的用例钉死。
 *
 * <p>方案见 {@code docs/technical/design/账期与对账放款-方案.md}。
 */
public final class SettleCycles {

    /** 次日结。默认档，现金流对商家最友好 */
    public static final String T_PLUS_1 = "T+1";

    /** 自然周结：T2 所在周的次周首日（周一） */
    public static final String WEEKLY = "WEEKLY";

    /** 半月结：1–15 日 → 16 日；16 日–月末 → 次月 1 日。与开票周期对齐 */
    public static final String SEMI_MONTHLY = "SEMI_MONTHLY";

    /** 月结：次月首日。<b>最容易撞冻结窗口</b>，用前先算 */
    public static final String MONTHLY = "MONTHLY";

    private SettleCycles() {
    }

    /**
     * T3 应结日：<b>那一天的零点</b>（按 {@code zone} 的自然日）。
     *
     * <p>取零点而不是「T2 加 N×24 小时」：账期说的是「哪一天到账」，
     * 不是「多少小时之后」。按小时算的话，同一天成交的两单会因为差几分钟
     * 落到不同的应结日，而商家看到的是「同一天的单，钱分两天到」。
     *
     * @param settleableAtMillis T2 可结算时刻
     * @param cycle              账期规则；<b>无法识别时按 {@link #T_PLUS_1}</b>，
     *                           见方法体的注释 —— 回落到最短的那一档是唯一安全的方向
     * @param zone               自然日/周/月的边界按哪个时区切
     */
    public static long dueAt(long settleableAtMillis, String cycle, ZoneId zone) {
        LocalDate t2 = Instant.ofEpochMilli(settleableAtMillis).atZone(zone).toLocalDate();
        LocalDate due = switch (normalize(cycle)) {
            case WEEKLY -> t2.plusWeeks(1).with(java.time.temporal.TemporalAdjusters
                    .previousOrSame(java.time.DayOfWeek.MONDAY));
            case SEMI_MONTHLY -> t2.getDayOfMonth() <= 15
                    ? t2.withDayOfMonth(16)
                    : t2.plusMonths(1).withDayOfMonth(1);
            case MONTHLY -> t2.plusMonths(1).withDayOfMonth(1);
            default -> t2.plusDays(days(cycle));
        };
        return due.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * 两个账期取<b>更短的那个</b>：主体配的与通道支持的。
     *
     * <p>方向不能反。通道只支持 T+1 而主体配了月结的话，按月结发出去的指令
     * 会在通道侧被拒 —— 而<b>拒绝理由不会说「因为你配了月结」</b>，
     * 排查的人只会看到一条通用的失败码。
     *
     * @param a 任一账期，null / 空按 {@link #T_PLUS_1}
     * @param b 同上
     */
    public static String shorter(String a, String b) {
        return worstCaseDays(a) <= worstCaseDays(b) ? normalize(a) : normalize(b);
    }

    /**
     * 这一档<b>最坏</b>要等多少天 —— 「谁更短」按它比。
     *
     * <p>为什么按最坏而不是平均：账期要与冻结窗口 {@code Tmax} 相减，
     * 而那个减法必须成立于<b>每一单</b>。用平均去比，撞上限的正是最倒霉的那一单，
     * 而它撞上时资金已经自动解冻、佣金已经收不到了。
     */
    public static int worstCaseDays(String cycle) {
        return switch (normalize(cycle)) {
            // 周结：T2 落在周一时要等满 7 天
            case WEEKLY -> 7;
            // 半月结：T2 落在 16 日时要等到次月 1 日，最长 16 天（大月）
            case SEMI_MONTHLY -> 16;
            // 月结：T2 落在月初 1 日时要等到次月 1 日，最长 31 天
            case MONTHLY -> 31;
            default -> days(cycle);
        };
    }

    /**
     * {@code T+N} 里的 N。
     *
     * <p><b>识别不了一律按 1</b>：回落到最短的那一档是唯一安全的方向 ——
     * 回落到更长的档意味着钱在通道那边多冻几天，而多冻的每一天都在逼近
     * 冻结窗口；识别不了就说明配置有问题，此时不该由它来决定钱等多久。
     */
    private static int days(String cycle) {
        String c = normalize(cycle);
        if (!c.startsWith("T+")) {
            return 1;
        }
        try {
            int n = Integer.parseInt(c.substring(2).trim());
            return n > 0 ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String normalize(String cycle) {
        return cycle == null || cycle.isBlank() ? T_PLUS_1 : cycle.trim().toUpperCase();
    }
}
