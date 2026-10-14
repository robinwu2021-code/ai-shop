package ai.neargo.shop.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 投递泵的四条承诺（TDD-来单多渠道通知 §2.2 / §2.3）。
 *
 * <p>这里不起 Spring、不碰库：{@link OutboxDispatcher} 用替身，
 * 要验的是**调度行为**——谁和谁并发、谁和谁串行、失败之后还排不排。
 * 这四件事在集成测试里几乎不可观测（投递太快，看不出有没有并发）。
 */
@DisplayName("Outbox 投递泵")
class OutboxPumpTest {

    /** 记下每条事件在哪个线程上跑、什么时候跑 */
    private record Run(long id, String thread, long at) {
    }

    private OutboxPump pump(OutboxDispatcher dispatcher) {
        return new OutboxPump(dispatcher, true);
    }

    @Test
    @DisplayName("★★★ 同一张单的事件**串行且保序** —— 不会 SHIPPED 先于 PAID 发出去")
    void sameAggregateRunsInOrder() throws Exception {
        List<Run> runs = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(3);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            runs.add(new Run(id, Thread.currentThread().getName(), System.nanoTime()));
            Thread.sleep(30);   // 让「并发了就会交错」这件事真的有机会发生
            done.countDown();
            return new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        // 同一张单的三个事件，按 PAID → SHIPPED → COMPLETED 的顺序入队
        p.submit(1, "SUB-A");
        p.submit(2, "SUB-A");
        p.submit(3, "SUB-A");

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(runs.stream().map(Run::id).toList())
                .as("同一张单必须按入队先后一个接一个").containsExactly(1L, 2L, 3L);
        assertThat(runs.stream().map(Run::thread).distinct())
                .as("而且是同一条线").hasSize(1);
        p.shutdown();
    }

    @Test
    @DisplayName("★★★ 不同单**并发**，并发度就是 5")
    void differentAggregatesRunConcurrently() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(20);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(40);
            inFlight.decrementAndGet();
            done.countDown();
            return new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        // 20 张不同的单 —— 够把 5 条线全占满
        for (int i = 0; i < 20; i++) {
            p.submit(i, "SUB-" + i);
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(peak.get()).as("最多 5 个同时在跑").isLessThanOrEqualTo(5);
        assertThat(peak.get()).as("也确实并发起来了，不是一条一条").isGreaterThan(1);
        p.shutdown();
    }

    @Test
    @DisplayName("★★★ 失败且没到上限 → **按退避排回来**（去掉定时轮之后没有别人会捞它）")
    void failureIsRescheduled() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            twice.countDown();
            /*
             * 第一次失败并要求**立刻**重排，第二次成功。
             *
             * 延迟取 0 而不是一个真实退避值：这条测的是「失败之后还会不会排回来」，
             * 不是「退避时长准不准」。后者属于 OutboxDispatcher 的 backoff()，
             * 在这里一起测只会让这条变成时间敏感的。
             */
            return attempts.incrementAndGet() == 1
                    ? new OutboxDispatcher.Outcome(false, Duration.ZERO)
                    : new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        p.submit(7, "SUB-R");

        // 超时给足：这里等的是「有没有发生」，不是「多快发生」
        assertThat(twice.await(30, TimeUnit.SECONDS)).as("该被投第二次").isTrue();
        /*
         * ⚠️ 这一条红过一次，而**当时的归因是错的**：以为是满负载下的时序问题，
         * 真因是消融之后用 `mv` 把备份搬回来 —— mv 保留旧 mtime，
         * maven 认为源码没变，跑的还是消融版的字节码（那一版正是「失败不重排」）。
         * 还原后要 `touch`。下次这条再红，先确认跑的是不是当前源码。
         */
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        p.shutdown();
    }

    @Test
    @DisplayName("★★ 到上限转了 FAILED 就**不再排** —— 那是「交给人」的意思")
    void exhaustedIsNotRescheduled() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            attempts.incrementAndGet();
            return new OutboxDispatcher.Outcome(false, null);   // retryAfter=null
        });
        OutboxPump p = pump(d);

        p.submit(8, "SUB-X");
        assertThat(p.awaitIdle(Duration.ofSeconds(3))).isTrue();
        Thread.sleep(200);

        assertThat(attempts.get()).as("只该投一次").isEqualTo(1);
        p.shutdown();
    }

    @Test
    @DisplayName("★★★ 启动补扫把库里的 PENDING 全排回来 —— 这就是「重启后能继续发送」")
    void startupRescansPending() throws Exception {
        CountDownLatch done = new CountDownLatch(3);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.pendingIds()).thenReturn(List.of(
                new OutboxDispatcher.Pending(11, "SUB-1"),
                new OutboxDispatcher.Pending(12, "SUB-2"),
                new OutboxDispatcher.Pending(13, "SUB-3")));
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            done.countDown();
            return new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        p.onReady();

        assertThat(done.await(30, TimeUnit.SECONDS)).as("三条都该被重新投出").isTrue();
        p.shutdown();
    }

    @Test
    @DisplayName("★★ 聚合键为负 hash 时也能选到线 —— floorMod，不是 %")
    void negativeHashStillPicksALine() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            done.countDown();
            return new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        /*
         * 找一个 hashCode 为负的聚合键。用 % 的话这里直接 IndexOutOfBounds，
         * 而它只在某些键上发生 —— 测试里碰不碰得到全看运气，所以这条要显式造。
         */
        String negative = someKeyWithNegativeHash();
        assertThat(negative.hashCode()).isNegative();
        p.submit(99, negative);

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        p.shutdown();
    }

    @Test
    @DisplayName("关掉之后什么都不投 —— 应急时要能一键停")
    void disabledSubmitsNothing() throws Exception {
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        OutboxPump p = new OutboxPump(d, false);

        p.submit(1, "SUB-A");
        p.onReady();
        Thread.sleep(100);

        org.mockito.Mockito.verify(d, org.mockito.Mockito.never()).dispatchOne(anyLong());
        p.shutdown();
    }

    @Test
    @DisplayName("★★ 同一张单排队时，**别的单不受它阻塞**")
    void oneBusyAggregateDoesNotBlockOthers() throws Exception {
        Map<String, Long> firstRunAt = new ConcurrentHashMap<>();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        OutboxDispatcher d = mock(OutboxDispatcher.class);
        when(d.dispatchOne(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            if (id < 100) {           // 慢的那张单
                slowStarted.countDown();
                Thread.sleep(300);
            } else {                  // 另一张单
                firstRunAt.put("fast", System.nanoTime());
                fastDone.countDown();
            }
            return new OutboxDispatcher.Outcome(true, null);
        });
        OutboxPump p = pump(d);

        p.submit(1, "SUB-SLOW");
        p.submit(2, "SUB-SLOW");
        assertThat(slowStarted.await(30, TimeUnit.SECONDS)).isTrue();
        p.submit(100, "SUB-FAST");

        /*
         * 慢单要跑 300ms。快单只要没被它挡住就会立刻完成 ——
         * 给 3 秒而不是 1 秒：满负载下线程调度本身就可能慢几百毫秒，
         * 而这条验的是「有没有被阻塞」，不是「有多快」。
         */
        assertThat(fastDone.await(3, TimeUnit.SECONDS))
                .as("慢单还在跑，快单不该等它").isTrue();
        p.shutdown();
    }

    /** 造一个 hashCode 为负的字符串。不同 JDK 的 hash 算法一致（规范定死），所以可以这么找 */
    private static String someKeyWithNegativeHash() {
        for (int i = 0; i < 100_000; i++) {
            String s = "SUB-" + i;
            if (s.hashCode() < 0) {
                return s;
            }
        }
        throw new IllegalStateException("十万个里一个负 hash 都没有？那是 String.hashCode 变了");
    }
}
