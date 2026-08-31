package ai.neargo.shop.pay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账期规则：算错的表现是<b>钱晚到或早到几天</b>，而商家不会有别的办法发现，
 * 只会觉得「这个平台说话不算数」。所以每一档都用具体日期钉死。
 */
class SettleCyclesTest {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 把「某天某时」变成毫秒，用来当 T2 */
    private static long at(int y, int m, int d, int hour) {
        return LocalDateTime.of(y, m, d, hour, 0).atZone(CN).toInstant().toEpochMilli();
    }

    private static LocalDate dueDate(long t2, String cycle) {
        return java.time.Instant.ofEpochMilli(SettleCycles.dueAt(t2, cycle, CN))
                .atZone(CN).toLocalDate();
    }

    @Test
    @DisplayName("★★ T+N：次日 / 第 N 日，且取的是**那一天的零点**")
    void tPlusN() {
        assertThat(dueDate(at(2026, 8, 30, 10), "T+1")).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(dueDate(at(2026, 8, 30, 10), "T+3")).isEqualTo(LocalDate.of(2026, 9, 2));

        /*
         * 同一天成交的两单必须落到同一个应结日。
         * 按「T2 加 N×24 小时」算的话，早上和深夜的两单会差一天 ——
         * 而商家看到的是「同一天的单，钱分两天到」。
         */
        assertThat(SettleCycles.dueAt(at(2026, 8, 30, 1), "T+1", CN))
                .as("凌晨那一单")
                .isEqualTo(SettleCycles.dueAt(at(2026, 8, 30, 23), "T+1", CN));
    }

    @Test
    @DisplayName("★★ 周结：T2 所在周的**次周周一**")
    void weekly() {
        // 2026-08-30 是周日，2026-08-31 是周一
        assertThat(dueDate(at(2026, 8, 30, 10), "WEEKLY"))
                .as("周日成交 → 次周周一，只等 1 天")
                .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(dueDate(at(2026, 8, 31, 10), "WEEKLY"))
                .as("周一成交 → 下下周一，要等满 7 天（最坏的那一档）")
                .isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    @DisplayName("★★ 半月结：1–15 → 16 日；16–月末 → 次月 1 日")
    void semiMonthly() {
        assertThat(dueDate(at(2026, 8, 1, 10), "SEMI_MONTHLY")).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(dueDate(at(2026, 8, 15, 10), "SEMI_MONTHLY")).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(dueDate(at(2026, 8, 16, 10), "SEMI_MONTHLY")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(dueDate(at(2026, 8, 31, 10), "SEMI_MONTHLY")).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("★★ 月结：次月首日，跨年也对")
    void monthly() {
        assertThat(dueDate(at(2026, 8, 5, 10), "MONTHLY")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(dueDate(at(2026, 12, 20, 10), "MONTHLY"))
                .as("12 月的单结到次年 1 月")
                .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("★★★ 认不出的账期回落到 T+1 —— 回落到更长的档等于让钱多冻几天")
    void unknownFallsBackToShortest() {
        for (String bad : new String[] {null, "", "   ", "T+", "T+abc", "T+0", "T+-3", "QUARTERLY"}) {
            assertThat(dueDate(at(2026, 8, 30, 10), bad))
                    .as("「%s」认不出，必须按 T+1", bad)
                    .isEqualTo(LocalDate.of(2026, 8, 31));
        }
    }

    @Test
    @DisplayName("★★★ 取更短的那一档 —— 方向反了的话指令会在通道侧被拒，而理由不会提账期")
    void shorterWins() {
        assertThat(SettleCycles.shorter("MONTHLY", "T+1")).isEqualTo("T+1");
        assertThat(SettleCycles.shorter("T+1", "MONTHLY")).isEqualTo("T+1");
        assertThat(SettleCycles.shorter("WEEKLY", "SEMI_MONTHLY")).isEqualTo("WEEKLY");
        assertThat(SettleCycles.shorter("T+3", "WEEKLY")).isEqualTo("T+3");
        assertThat(SettleCycles.shorter("T+10", "WEEKLY"))
                .as("T+10 比周结长，该取周结")
                .isEqualTo("WEEKLY");
        assertThat(SettleCycles.shorter(null, "MONTHLY"))
                .as("空按 T+1，所以空永远是更短的那个")
                .isEqualTo("T+1");
    }

    @Test
    @DisplayName("★★★ 最坏天数按**最坏**算 —— 与冻结窗口相减时，平均值救不了最倒霉的那一单")
    void worstCaseDaysIsWorstNotAverage() {
        assertThat(SettleCycles.worstCaseDays("T+1")).isEqualTo(1);
        assertThat(SettleCycles.worstCaseDays("T+5")).isEqualTo(5);
        assertThat(SettleCycles.worstCaseDays("WEEKLY")).isEqualTo(7);
        assertThat(SettleCycles.worstCaseDays("SEMI_MONTHLY")).isEqualTo(16);
        assertThat(SettleCycles.worstCaseDays("MONTHLY")).isEqualTo(31);

        /*
         * 这一条是给「账期能不能配成月结」那道校验用的：
         * 微信的冻结窗口若是 30 天，MONTHLY 的最坏 31 天就已经超了 ——
         * 而按「平均 15 天」去比的话它会通过，然后在某个月初成交的单上翻车。
         */
        assertThat(SettleCycles.worstCaseDays("MONTHLY"))
                .as("月结最坏 31 天，已经超过 30 天的冻结窗口")
                .isGreaterThan(30);
    }
}
