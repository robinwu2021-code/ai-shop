package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.LinkHealthService;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 两条方向各一条聚合查询。
 *
 * <p><b>两条链路的表在两个库里</b>（{@code sys_outbox} 在平台库、{@code inv_outbox}
 * 在进销存库），所以这一页只能落在装配层 —— 与 {@code InventoryHealthService} 同理。
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class LinkHealthServiceImpl implements LinkHealthService {

    /**
     * 一条积压活过多久算「链路停了」。
     *
     * <p>投递任务的 cron 每 5 秒一轮（{@code shop.job.outbox.cron} 默认值）。
     * 5 分钟 = 60 轮：正常情况下不可能有一条事件活这么久，
     * 而 09-02 那次是六个小时。取这个值是为了「宽到不误报、紧到当天就发现」。
     */
    private static final int STALL_MINUTES = 5;

    private final SysOutboxMapper sysOutbox;
    private final OutboxMapper invOutbox;

    public LinkHealthServiceImpl(SysOutboxMapper sysOutbox, OutboxMapper invOutbox) {
        this.sysOutbox = sysOutbox;
        this.invOutbox = invOutbox;
    }

    @Override
    public List<ChannelHealth> scan() {
        LocalDateTime stallBefore = LocalDateTime.now().minusMinutes(STALL_MINUTES);
        return List.of(
                platformToInventory(stallBefore),
                inventoryToPlatform(stallBefore));
    }

    private ChannelHealth platformToInventory(LocalDateTime stallBefore) {
        Map<String, Object> agg = one(sysOutbox.selectMaps(Wrappers.<SysOutbox>query()
                .select(PENDING_AGG)
                .eq("status", SysOutbox.PENDING)));
        Map<String, Object> sent = one(sysOutbox.selectMaps(Wrappers.<SysOutbox>query()
                .select("MAX(sent_at) AS last_sent")
                .eq("status", SysOutbox.SENT)));
        return build(Channel.PLATFORM_TO_INVENTORY, agg, sent, stallBefore);
    }

    private ChannelHealth inventoryToPlatform(LocalDateTime stallBefore) {
        Map<String, Object> agg = one(invOutbox.selectMaps(Wrappers.<InvOutbox>query()
                .select(PENDING_AGG)
                .eq("status", "PENDING")));
        Map<String, Object> sent = one(invOutbox.selectMaps(Wrappers.<InvOutbox>query()
                .select("MAX(sent_at) AS last_sent")
                .eq("status", "SENT")));
        return build(Channel.INVENTORY_TO_PLATFORM, agg, sent, stallBefore);
    }

    /**
     * 两张表的列名一致，聚合式因此可以共用。
     *
     * <p>**分开数 retry=0 与 retry>0**：这是这一页存在的理由 ——
     * 「没人碰过」与「一直在失败」是两件事，而 status 那一列对此一无所知
     * （两个投递任务失败时都把事件留在 PENDING）。
     */
    private static final String[] PENDING_AGG = {
            "COUNT(*) AS pending",
            "SUM(CASE WHEN retry_count IS NULL OR retry_count = 0 THEN 1 ELSE 0 END) AS never_tried",
            "SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) AS retrying",
            "MIN(created_at) AS oldest",
            "MAX(retry_count) AS max_retry",
            "MAX(last_error) AS last_error",
    };

    private static ChannelHealth build(String channel, Map<String, Object> agg,
                                       Map<String, Object> sent, LocalDateTime stallBefore) {
        long pending = num(agg.get("pending"));
        long neverTried = num(agg.get("never_tried"));
        long retrying = num(agg.get("retrying"));
        LocalDateTime oldest = time(agg.get("oldest"));
        long maxRetry = num(agg.get("max_retry"));
        String lastError = retrying > 0 ? str(agg.get("last_error")) : null;

        return new ChannelHealth(channel, pending, neverTried, retrying, oldest,
                time(sent.get("last_sent")), maxRetry, lastError,
                verdict(pending, retrying, oldest, stallBefore));
    }

    /**
     * 结论。**顺序有意义**：
     *
     * <ol>
     *   <li>没积压 → OK。</li>
     *   <li>积压都还新鲜 → BACKLOG，投递任务每 5 秒一轮，这是正常的处理中。</li>
     *   <li>老积压 + 有重试过的 → 消费者在抛异常。</li>
     *   <li>老积压 + 一条都没被碰过 → 投递任务没在跑。<b>09-02 那次就落在这一档。</b></li>
     * </ol>
     *
     * <p>「有重试」排在「没人碰过」前面：投递任务停了的话重试次数也不会再涨，
     * 所以「有 retry &gt; 0 的老条目」说明它<b>跑过</b>，问题在消费者那一头。
     */
    private static String verdict(long pending, long retrying,
                                  LocalDateTime oldest, LocalDateTime stallBefore) {
        if (pending == 0) {
            return Verdict.OK;
        }
        if (oldest == null || oldest.isAfter(stallBefore)) {
            return Verdict.BACKLOG;
        }
        return retrying > 0 ? Verdict.CONSUMER_FAILING : Verdict.DISPATCHER_STALLED;
    }

    /**
     * 聚合查询的那一行。
     *
     * <p><b>它可能是一个 null 元素</b>，而不是空列表：一条都不匹配时
     * {@code MAX(sent_at)} 那行的每一列都是 null，MyBatis 把「全列为 null 的行」
     * 映射成 {@code null} 本身。于是 {@code rows.get(0)} 是 null 而 {@code isEmpty()} 是 false ——
     * 只判空列表的话，<b>队列是空的（也就是一切正常）</b>这个最常见的状态直接 500。
     */
    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
        return row == null ? Map.of() : row;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** H2 给 LocalDateTime，MariaDB 驱动给 Timestamp —— 两种都要收 */
    private static LocalDateTime time(Object o) {
        return o instanceof LocalDateTime t ? t
                : o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
    }
}
