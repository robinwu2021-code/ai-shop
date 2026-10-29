package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.service.OperationalStatsService;
import ai.neargo.inventory.guard.InstanceProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * 监控指标（TDD §2.18 T6.2，执行方案「监控分套告警」）。每个指标带实例代号：
 * 生产两套各自告警，告警名一眼看出是电商套还是设备套。
 *
 * <ul>
 *   <li>{@code inventory.events.pending}：事件积压（宿主不来拉）</li>
 *   <li>{@code inventory.events.unacked}：游标延迟（拉了不确认）</li>
 *   <li>{@code inventory.counts.open.oldest.minutes}：盘点冻结超时</li>
 *   <li>{@code inventory.holds.return_pending.oldest.minutes}：退货待确认超时</li>
 *   <li>{@code inventory.errors}（按错误码，见 {@code ApiErrorAdvice}）：命令失败率与冲突率</li>
 * </ul>
 */
@Component
public class InventoryMetrics implements MeterBinder {

    private final OperationalStatsService stats;
    private final InstanceProperties instance;

    public InventoryMetrics(OperationalStatsService stats, InstanceProperties instance) {
        this.stats = stats;
        this.instance = instance;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("instance", instance.getCode());
        Gauge.builder("inventory.events.pending", stats, OperationalStatsService::pendingEvents)
                .tags(tags).register(registry);
        Gauge.builder("inventory.events.unacked", stats, OperationalStatsService::unackedEvents)
                .tags(tags).register(registry);
        Gauge.builder("inventory.counts.open.oldest.minutes", stats, OperationalStatsService::oldestOpenCountMinutes)
                .tags(tags).register(registry);
        Gauge.builder("inventory.holds.return_pending.oldest.minutes", stats,
                OperationalStatsService::oldestReturnHoldMinutes).tags(tags).register(registry);
    }
}
