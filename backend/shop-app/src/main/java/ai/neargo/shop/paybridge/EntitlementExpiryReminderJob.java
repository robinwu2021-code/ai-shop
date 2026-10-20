package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cursor only schedules fair scans; the durable inbox key deduplicates rescans and process restarts. */
@Component
@ConditionalOnProperty(name="shop.job.enabled",havingValue="true")
public class EntitlementExpiryReminderJob implements JobHandler {
    private final EntitlementExpiryReminderService reminders;
    private long cursor;
    private final JobSupport jobs;
    public EntitlementExpiryReminderJob(EntitlementExpiryReminderService reminders,JobSupport jobs) { this.reminders=reminders; this.jobs=jobs; }
    @Override public String name() { return "entitlement-expiry-reminder"; }
    @Scheduled(cron="${shop.job.entitlement-expiry-reminder.cron:15 */15 * * * *}")
    @SchedulerLock(name="entitlement-expiry-reminder",lockAtMostFor="PT4M")
    public void tick() { jobs.run(name(),() -> run(null).detail()); }
    @Bean public JobDeclaration entitlementExpiryReminderDeclaration() {
        return new JobDeclaration(name(),"权益到期提醒","24小时内到期站内提示，失败不改变权益",
                "shop-app","15 */15 * * * *",true,240,900,true,true);
    }
    @Override public synchronized JobResult run(JobInvocation invocation) {
        var result = reminders.run(cursor, 100);
        cursor = result.nextId();
        return JobResult.ok(result.scanned() == 0 ? null : "扫描%d项，消息失败%d项".formatted(
                result.scanned(), result.failed()));
    }
}
