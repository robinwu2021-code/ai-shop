package ai.neargo.shop.trade.support;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

/**
 * 订单号生成（ADR-035 短期号段方案）。
 *
 * <pre>
 *   主单：O + yyMMddHH + NNNN        例 O261010140001
 *   子单：&lt;主单号&gt; + "-" + 序号     例 O261010140001-1
 * </pre>
 *
 * <h2>号段，不是每笔 +1</h2>
 * 序列从 DB 游标领段（{@link SeqSegmentSource}，一次 {@value #STEP} 个），段内在内存发，
 * 发完再领。于是 {@value #STEP} 笔才一次 DB 往返；重启后内存段作废、DB 游标已前进，领新段
 * 自然接上、不重号；多实例各领各的段、天然不重叠（见 ADR-035）。
 *
 * <h2>为什么整个 {@link #nextMainSeq} 加锁，而不是无锁 CAS</h2>
 * 领段 {@value #STEP} 笔才一次、订单创建本就不是超高频（还隔着库存锁等更重的操作），
 * {@code synchronized} 的争用可忽略，换来的是**无浪费、显然正确**：无锁 CAS 的那套
 * 双 buffer 要处理「并发冲过段尾、作废重试」，复杂且会浪费号，对这里的量得不偿失。
 */
@Component
public class OrderNoGenerator {

    /** 段长：一次领 10 个。改大 = DB 往返更少、重启浪费更多；改小反之 */
    static final int STEP = 10;

    /** 展示序列的位数与循环上限：4 位 → 1..9999。将来扩 5 位改这两个常量即可 */
    private static final int DISPLAY_WIDTH = 4;
    private static final long DISPLAY_MOD = 9999L;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyMMddHH")
            .withZone(ZoneId.systemDefault());

    private final SeqSegmentSource source;

    /** 当前段游标与上界（闭区间）。cursor &gt; hi 表示段已用尽，要领新段。全程在 synchronized 下访问 */
    private long cursor = 1;
    private long hi = 0;

    public OrderNoGenerator(SeqSegmentSource source) {
        this.source = source;
    }

    /** 领一个序列值（DB 单调递增、永不回绕的那个原值） */
    synchronized long nextMainSeq() {
        if (cursor > hi) {
            long[] seg = source.claim("ord_main", STEP);
            cursor = seg[0];
            hi = seg[1];
        }
        return cursor++;
    }

    /** 把 DB 序列值格式化成 4 位展示：1..9999 循环，满了回绕。永远不是 0000 */
    public static String display(long seq) {
        long n = ((seq - 1) % DISPLAY_MOD) + 1;   // 1..9999
        return String.format("%0" + DISPLAY_WIDTH + "d", n);
    }

    /**
     * 主单号。{@code taken} 判断这个号是否已被占（撞 uk_order_no 的兜底，号段正常不撞）——
     * 撞了就跳过、领下一个号，限 {@value #MAX_PROBE} 次。
     */
    private static final int MAX_PROBE = 3;

    public String mainOrderNo(long epochMilli, Predicate<String> taken) {
        String hour = HOUR.format(Instant.ofEpochMilli(epochMilli));
        for (int i = 0; i < MAX_PROBE; i++) {
            String no = "O" + hour + display(nextMainSeq());
            if (taken == null || !taken.test(no)) {
                return no;
            }
        }
        // 连 MAX_PROBE 个都撞：同一小时内号段严重回绕（> 9999 单/时），这不该发生
        throw new IllegalStateException("订单号连续 " + MAX_PROBE + " 次撞号，序列空间在本小时内耗尽");
    }

    /** 不带兜底的重载：测试与不需要校验的调用方用 */
    public String mainOrderNo(long epochMilli) {
        return mainOrderNo(epochMilli, null);
    }

    /** 子单号 = 主单号 + "-" + 序号（1-based）。不消耗序列 */
    public String subOrderNo(String mainNo, int index) {
        return mainNo + "-" + index;
    }
}
