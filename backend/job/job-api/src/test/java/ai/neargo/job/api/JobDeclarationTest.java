package ai.neargo.job.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声明的自校验。
 *
 * <p>盯的是 2026-08-28 复核查出来的那个形态：{@code timeoutSec} 与
 * {@code lockAtMostSec} <b>都在回答「这任务最长跑多久」，却给了相差 30 倍的答案</b>。
 * 11 个任务里 6 个如此 —— 因为 {@code daily()} 里写死了那一对，没人为具体任务想过。
 */
class JobDeclarationTest {

    private static JobDeclaration of(int timeoutSec, int lockAtMostSec) {
        return new JobDeclaration("j", "名", "说明", "mod", "0 0 3 * * *",
                true, timeoutSec, lockAtMostSec, true, true);
    }

    @Test
    @DisplayName("★★★ 超时与持锁差太远就拒绝 —— 两个数字在说不同的事")
    void rejectsInconsistentTimeoutAndLock() {
        // 这正是修复前生产上的那一对
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> of(60, 1800));
        assertTrue(e.getMessage().contains("30 倍"), e.getMessage());
        assertTrue(e.getMessage().contains("timeout"), "报错要说清楚该怎么改");
    }

    @Test
    @DisplayName("★★ 锁比超时短直接拒绝 —— 锁会在调用还没结束时释放")
    void rejectsLockShorterThanTimeout() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> of(300, 120));
        assertTrue(e.getMessage().contains("另一个实例"), e.getMessage());
    }

    @Test
    @DisplayName("留余量是允许的：4 倍以内都算「同一个预期」")
    void allowsReasonableSlack() {
        assertDoesNotThrow(() -> of(60, 60));
        assertDoesNotThrow(() -> of(60, 240));
        assertThrows(IllegalArgumentException.class, () -> of(60, 241));
    }

    @Test
    @DisplayName("★ daily() 给出的那一对必须自洽 —— 它是 6 个任务的来源")
    void dailyDefaultIsSelfConsistent() {
        JobDeclaration d = assertDoesNotThrow(
                () -> JobDeclaration.daily("j", "名", "说明", "mod", "0 0 3 * * *"));
        assertTrue(d.lockAtMostSec() >= d.timeoutSec());
        assertTrue(d.lockAtMostSec() <= d.timeoutSec() * 4);
        assertTrue(d.timeoutSec() >= 300,
                "夜间维护任务在数据量长起来之后跑几分钟是正常的，别再回到 60 秒");
    }
}
