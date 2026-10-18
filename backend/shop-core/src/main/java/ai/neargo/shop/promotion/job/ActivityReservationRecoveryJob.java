package ai.neargo.shop.promotion.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.promotion.service.ActivityParticipationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A deadline triggers a source query, never an inferred cancellation or a blind budget release. */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class ActivityReservationRecoveryJob implements JobHandler {
    private final ActivityParticipationService participations;
    private final JobSupport jobs;
    public ActivityReservationRecoveryJob(ActivityParticipationService participations, JobSupport jobs) {
        this.participations = participations; this.jobs = jobs;
    }
    @Scheduled(cron = "${shop.job.activity-reservation-recovery.cron:15 * * * * *}")
    @SchedulerLock(name = "activity-reservation-recovery", lockAtLeastFor = "PT15S", lockAtMostFor = "PT3M")
    public void tick() { jobs.run(name(), () -> run(null).detail()); }
    @Override public String name() { return "activity-reservation-recovery"; }
    @Bean public JobDeclaration activityReservationRecoveryDeclaration() {
        return new JobDeclaration(name(), "活动参与在途恢复", "查询可信业务终态；未知状态保留名额及预算占用",
                "shop-core", "15 * * * * *", true, 50, 180, true, false);
    }
    @Override public JobResult run(JobInvocation invocation) {
        int recovered = participations.recoverPending(100);
        return JobResult.ok(recovered == 0 ? null : "恢复活动参与 " + recovered + " 项");
    }
}
