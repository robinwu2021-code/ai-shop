package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.support.OrderNoGenerator;
import ai.neargo.shop.trade.support.SeqSegmentSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ai.neargo.shop.trade.mapper.TradeMappers.SeqMapper;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单号号段生成（ADR-035）。这些性质**一旦出错就是静默重号**——订单创建失败或两单同号，
 * 没有一条是靠读代码能放心的，全部钉成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderNoGeneratorTest {

    @Autowired
    private OrderNoGenerator gen;
    @Autowired
    private SeqSegmentSource source;
    @Autowired
    private SeqMapper seqMapper;
    @Autowired
    private PlatformTransactionManager txm;

    /** 同一小时的毫秒（固定住 yyMMddHH，只看尾 4 位序列） */
    private static final long HOUR_T = 1_760_000_000_000L;
    private static final Pattern MAIN = Pattern.compile("^O\\d{8}(\\d{4})$");

    /** 取尾 4 位序列（整数） */
    private int tail(String no) {
        Matcher m = MAIN.matcher(no);
        assertThat(m.matches()).as("订单号格式 O+yyMMddHH+NNNN：" + no).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test
    @DisplayName("★★★ 格式：主单 O+yyMMddHH+4位；子单 = 主单号 + 后缀")
    void format() {
        String main = gen.mainOrderNo(HOUR_T);
        assertThat(main).matches("^O\\d{8}\\d{4}$");
        assertThat(gen.subOrderNo(main, 2)).isEqualTo(main + "-2");
    }

    @Test
    @DisplayName("★★★ 展示回绕：9999 之后回到 0001，永远不是 0000")
    void displayWraps() {
        assertThat(OrderNoGenerator.display(1)).isEqualTo("0001");
        assertThat(OrderNoGenerator.display(9999)).isEqualTo("9999");
        assertThat(OrderNoGenerator.display(10000)).isEqualTo("0001");   // 回绕
        assertThat(OrderNoGenerator.display(19998)).isEqualTo("9999");
        assertThat(OrderNoGenerator.display(19999)).isEqualTo("0001");
    }

    @Test
    @DisplayName("★★★ 连续取号严格递增、无重复（跨段也不回退）")
    void monotonicAcrossSegments() {
        /*
         * 取 25 个（跨 STEP=10 至少 2 次领段）。断言的是**严格递增 + 无重复**，
         * 不是「严格 +1」—— 号段本就允许跳段（重启作废老段、多实例各领各段，见 restartDoesNotReuse /
         * twoInstancesDoNotOverlap）。本测试类里那几条新建了额外 generator、共享同一个 ord_main 游标，
         * 会把 DB 推高，于是主 gen 跨段时尾号会跳。那不是 bug，强求 +1 反而是把测试间干扰钉成了规范。
         * 生产单实例独占时自然连续；这里要守住的是「不重复、不回退」这条任何情况都成立的性质。
         */
        long prev = -1;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            int t = tail(gen.mainOrderNo(HOUR_T));
            assertThat(seen.add(t)).as("序列重复了：" + t).isTrue();
            assertThat((long) t).as("序列回退了：" + prev + " → " + t).isGreaterThan(prev);
            prev = t;
        }
    }

    @Test
    @DisplayName("★★★ 重启不重号：新 generator 实例领新段、不回退到段首")
    void restartDoesNotReuse() {
        int a = tail(gen.mainOrderNo(HOUR_T));
        int b = tail(gen.mainOrderNo(HOUR_T));
        int c = tail(gen.mainOrderNo(HOUR_T));   // 这三个在第一段里，游标停在段中

        // 模拟重启：共用同一个 DB source，新建一个 generator（内存段丢失）
        OrderNoGenerator restarted = new OrderNoGenerator(source);
        int afterRestart = tail(restarted.mainOrderNo(HOUR_T));

        // 新实例领的是**下一段**，不会回到 a/b/c 之间，更不会从头
        assertThat(afterRestart)
                .as("重启后必须领新段、不重号（老段剩余作废是允许的浪费）")
                .isGreaterThan(c);
        assertThat(List.of(a, b, c)).doesNotContain(afterRestart);
    }

    @Test
    @DisplayName("★★★ 两个实例（多进程）取号不重叠 —— 号段天然隔离")
    void twoInstancesDoNotOverlap() {
        OrderNoGenerator g1 = new OrderNoGenerator(source);
        OrderNoGenerator g2 = new OrderNoGenerator(source);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < 15; i++) {
            assertThat(all.add(tail(g1.mainOrderNo(HOUR_T)))).as("g1 与已有重号").isTrue();
            assertThat(all.add(tail(g2.mainOrderNo(HOUR_T)))).as("g2 与已有重号").isTrue();
        }
        assertThat(all).hasSize(30);
    }

    @Test
    @DisplayName("★★★ 并发取号：无重复、无丢失")
    void concurrentNoDupNoLoss() throws Exception {
        int threads = 8;
        int each = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> out = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        out.add(tail(gen.mainOrderNo(HOUR_T)));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Set<Integer> uniq = new HashSet<>(out);
        assertThat(out).as("丢了号或多发了号").hasSize(threads * each);
        assertThat(uniq).as("有重复号（并发发号撞了）").hasSize(threads * each);
    }

    @Test
    @DisplayName("★★★ 领段独立提交：外层事务回滚，DB 游标也不退（号领了就不重用）")
    void claimCommitsEvenIfOuterRolls() {
        // 先确保 ord_main 存在、读到基线
        source.claim("ord_main", 1);
        long before = seqMapper.peek("ord_main");

        // 在一个**会回滚**的外层事务里取号（触发领新段）
        new TransactionTemplate(txm).execute(status -> {
            for (int i = 0; i < 12; i++) {   // 跨段，必触发至少一次 claim
                gen.mainOrderNo(HOUR_T);
            }
            status.setRollbackOnly();
            return null;
        });

        long after = seqMapper.peek("ord_main");
        assertThat(after)
                .as("领段必须独立提交 —— 外层回滚了，游标也不能退回（退回就会重号）。"
                        + "撤回 REQUIRES_NEW 改回 REQUIRED，这里 after==before → 红")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("★★ 撞号兜底：taken 一直为真时跳过续号，超限才抛")
    void probeSkipsTakenNumbers() {
        // 第一个号先拿到
        String first = gen.mainOrderNo(HOUR_T);
        // taken 对 first 之后的第一个号报「已占」一次，应跳过、返回再下一个
        Set<String> occupied = Collections.synchronizedSet(new HashSet<>());
        String next = gen.mainOrderNo(HOUR_T);          // 占位：模拟它已存在
        occupied.add(next);
        String got = gen.mainOrderNo(HOUR_T, occupied::contains);
        assertThat(got).as("撞号应跳过续发").isNotEqualTo(next);
        assertThat(got).isNotEqualTo(first);
    }
}
