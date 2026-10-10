package ai.neargo.shop.paybridge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「同一个持续状况，隔多久再说一遍」—— 给按轮次跑的巡检用的说话节奏器。
 *
 * <h2>它解决的是哪种缺陷</h2>
 * 巡检任务发现问题时打一条 WARN，本身没错。错的是<b>它每一轮都原样再打一条</b>：
 * 而问题往往一连几天都不好，于是
 *
 * <ul>
 *   <li>{@code recon-scan} 8~9 分钟一轮 → 一天约 167 条一模一样的 WARN，
 *       线上一次 524 行取样里它一个人占了 WARN 通道的 <b>52%</b>；</li>
 *   <li>{@code fund-invariant} 一小时一轮 → 一天 24 条。</li>
 * </ul>
 *
 * 后果<b>不是占盘</b>（日志有封顶，见「运维-目录与日志方案」§4）。后果是那条告警
 * 自己写着「要人立刻去看」，而重复上百次恰恰毁掉了这个性质：
 * 看的人分不出「这是新出的，还是坏了好几天」。与 §11.2 记的
 * 「ERROR 通道被 AuthorizationDenied 占满」是同一个形状。
 *
 * <h2>三种时刻，说三种话</h2>
 * <ul>
 *   <li><b>刚进入</b> → {@link State#first()} 为真，调用方照打完整告警，一刻不推迟；</li>
 *   <li><b>持续中</b> → 每 {@code restateEvery} 才返回一次，且带上
 *       {@linkplain State#rounds() 多少轮} / {@linkplain State#minutes() 多少分钟} /
 *       {@linkplain State#since() 自何时起}。一条「持续 167 轮、自 09-15 09:40 起」
 *       比 167 条一模一样的更有信息量；</li>
 *   <li><b>恢复了</b> → {@link #recovered} 返回它坏了多久，让调用方留一条痕。
 *       <b>这一条是此前完全没有的</b>：好了没人知道，只能靠「WARN 不再出现」去反推，
 *       而那与「任务挂了、压根没跑」长得一模一样。</li>
 * </ul>
 *
 * <h2>刻意不做的</h2>
 * <b>它只管「同一件事说几遍」，不管「还说不说」。</b> 它不降级、不静音、不吞：
 * 第一次永远原速原级别打出去。修日志噪音时把问题一起消音，是比噪音更坏的结果。
 *
 * <p>状态只在内存里，<b>不进任何账</b>：进程重启后从头算，代价是重启后多打一条 ——
 * 而那条恰好是重启后第一次，本来就该打。
 *
 * <p><b>线程安全的边界</b>：进出用 {@link ConcurrentHashMap}，但 {@code lastLogged}
 * 是在 {@code compute} 之外改的 —— 两条线程同轮撞上最坏是多打一条重述，不会漏打、不会错算轮次。
 * 两条巡检轴各持一个实例，且各自在 shedlock 下单线程跑，实际撞不上。
 */
final class StuckStateLog {

    private final Duration restateEvery;
    private final Map<String, Entry> stuck = new ConcurrentHashMap<>();

    StuckStateLog(Duration restateEvery) {
        this.restateEvery = restateEvery;
    }

    /**
     * 一段持续状况的现状。
     *
     * @param first   这一轮才刚进入 —— 调用方该打完整告警
     * @param rounds  连续第几轮（含这一轮）
     * @param minutes 从第一次发现到现在多少分钟
     * @param since   第一次发现的时刻
     */
    record State(boolean first, int rounds, long minutes, Instant since) {
    }

    /**
     * 报告「{@code key} 这件事这一轮还是坏的」。
     *
     * @return {@code null} = 这一轮别说话（还在重述间隔内）；否则按 {@link State} 说
     */
    State stillBad(String key, Instant now) {
        Entry e = stuck.compute(key, (k, cur) -> cur == null ? new Entry(now) : cur.advance());
        if (e.rounds == 1) {
            return new State(true, 1, 0, e.since);
        }
        // 到点才重述。注意比的是 lastLogged 而不是 since —— 否则第二个窗口起
        // 每一轮都满足条件，就退化回「每轮都打」了
        if (now.isBefore(e.lastLogged.plus(restateEvery))) {
            return null;
        }
        e.lastLogged = now;
        return new State(false, e.rounds, Duration.between(e.since, now).toMinutes(), e.since);
    }

    /**
     * 报告「{@code key} 这件事这一轮是好的」。
     *
     * @return {@code null} = 它本来就是好的（没什么可说）；否则它刚才坏了多久
     */
    State recovered(String key, Instant now) {
        Entry e = stuck.remove(key);
        if (e == null) {
            return null;
        }
        return new State(false, e.rounds, Duration.between(e.since, now).toMinutes(), e.since);
    }

    private static final class Entry {
        final Instant since;
        final int rounds;
        Instant lastLogged;

        Entry(Instant now) {
            this.since = now;
            this.rounds = 1;
            this.lastLogged = now;
        }

        private Entry(Instant since, int rounds, Instant lastLogged) {
            this.since = since;
            this.rounds = rounds;
            this.lastLogged = lastLogged;
        }

        Entry advance() {
            return new Entry(since, rounds + 1, lastLogged);
        }
    }
}
