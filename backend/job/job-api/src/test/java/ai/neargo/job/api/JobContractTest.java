package ai.neargo.job.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 契约里那几条**被违反时不会有任何声音**的约束。
 *
 * <p>这个包零依赖、没有运行时，唯一能守住语义的就是这些断言。
 */
class JobContractTest {

    @Test
    @DisplayName("业务侧不能返回 TIMEOUT/UNREACHABLE/RUNNING —— 那是 worker 收不到回答时的判断")
    void businessSideCannotClaimWorkerOnlyStatuses() {
        for (JobStatus s : new JobStatus[]{JobStatus.TIMEOUT, JobStatus.UNREACHABLE, JobStatus.RUNNING}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new JobResult(s, "d", null));
            assertTrue(e.getMessage().contains(s.name()), "报错要指名是哪个状态：" + e.getMessage());
        }
        assertDoesNotThrow(() -> JobResult.ok("关闭 12 单"));
        assertDoesNotThrow(() -> JobResult.failed("失败", "IllegalStateException"));
        assertDoesNotThrow(JobResult::skipped);
    }

    @Test
    @DisplayName("只有 FAILED 计入连续失败 —— SKIPPED 和 TIMEOUT 都不是故障")
    void onlyFailedCountsAsFailure() {
        assertTrue(JobStatus.FAILED.countsAsFailure());
        // 这三条是重点：把它们算进去，告警会在一切正常时响，而那样的告警等于没有告警
        assertFalse(JobStatus.SKIPPED.countsAsFailure(), "锁没抢到是正常的并发保护");
        assertFalse(JobStatus.TIMEOUT.countsAsFailure(), "超时是结果未知，业务侧多半还在跑");
        assertFalse(JobStatus.SUCCESS.countsAsFailure());
        assertFalse(JobStatus.UNREACHABLE.countsAsFailure(), "调不通是 worker 侧的事，不是任务失败");
    }

    @Test
    @DisplayName("RUNNING 之外都是终态")
    void onlyRunningIsNonTerminal() {
        for (JobStatus s : JobStatus.values()) {
            assertEquals(s != JobStatus.RUNNING, s.isTerminal(), s.name());
        }
    }

    @Test
    @DisplayName("runId 不能为空 —— 两边日志靠它对齐，缺了就只能靠时间戳猜")
    void runIdIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobInvocation(null, TriggerType.CRON, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JobInvocation("  ", TriggerType.CRON, null, null));
    }

    @Test
    @DisplayName("params 防御性拷贝：调用方之后改自己的 map，不能影响已发出的 invocation")
    void paramsAreDefensivelyCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("channel", "WECHAT"));
        JobInvocation in = new JobInvocation("r1", TriggerType.CRON, null, mutable);

        mutable.put("channel", "ALIPAY");
        mutable.put("injected", "x");

        assertEquals("WECHAT", in.param("channel", "?"), "拷贝没生效，参数会被调用方事后改掉");
        assertEquals("?", in.param("injected", "?"));
        assertThrows(UnsupportedOperationException.class, () -> in.params().put("k", "v"));
    }

    @Test
    @DisplayName("null params 归一成空 map，handler 不用判空")
    void nullParamsBecomeEmpty() {
        JobInvocation in = new JobInvocation("r1", TriggerType.CRON, null, null);
        assertTrue(in.params().isEmpty());
        assertEquals("默认", in.param("nope", "默认"));
    }

    @Test
    @DisplayName("声明里的非法值当场炸，不要等到跑起来才发现 cron 是空的")
    void declarationValidatesEagerly() {
        assertThrows(IllegalArgumentException.class, () ->
                new JobDeclaration("h", "名", "d", "m", "", true, 60, 180, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new JobDeclaration("h", "名", "d", "m", "0 0 3 * * *", true, 0, 180, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new JobDeclaration("h", "名", "d", "m", "0 0 3 * * *", true, 60, -1, true, true));

        JobDeclaration d = JobDeclaration.daily("plan-expiry", "增值包到期扫描",
                "扫出已过期的商家增值包并收回权益", "shop-merchant", "0 25 3 * * *");
        assertEquals("增值包到期扫描", d.displayName());
        assertTrue(d.enabled());
        /*
         * **原先这里钉的是 1800**，与 timeoutSec=60 配成一对 —— 而那一对正是
         * 2026-08-28 复核查出来的问题源头：两个数字都在回答「这任务最长跑多久」，
         * 却差了 30 倍。改成 600/900 之后 JobDeclaration 自己会拒绝那种组合，
         * 所以这里不再钉死具体数字，只钉「两者自洽」这条不变量 ——
         * 钉死数字的话，下次调整 daily() 又要改这里，而这个断言本身说不出为什么。
         */
        assertTrue(d.lockAtMostSec() >= d.timeoutSec()
                        && d.lockAtMostSec() <= d.timeoutSec() * 4,
                "daily() 给的超时与持锁必须自洽：" + d.timeoutSec() + " / " + d.lockAtMostSec());
    }
}
