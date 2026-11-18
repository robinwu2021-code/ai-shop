package ai.neargo.shop.reportbridge.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.reportbridge.service.ReportDailyService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The sole new regular FOOD report handler. Params never grant identity or store scope. */
@Component
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled","shop.job.enabled"},havingValue="true")
@ConditionalOnProperty(name="shop.report.access-role",havingValue="WRITER",matchIfMissing=true)
public class FoodReportDailyJob implements JobHandler {
    private static final String NAME="food-report-daily";
    private final ReportDailyService service;
    private final JobSupport jobs;
    public FoodReportDailyJob(ReportDailyService service,JobSupport jobs) { this.service=service; this.jobs=jobs; }
    @Override public String name() { return NAME; }
    @Scheduled(cron="${shop.job.food-report-daily.cron:0 30 4 * * *}",zone="${shop.job.food-report-daily.zone:Asia/Shanghai}")
    @SchedulerLock(name=NAME,lockAtLeastFor="PT1M",lockAtMostFor="PT2H")
    public void daily() {
        jobs.run(NAME,()-> {
            var result=service.runDaily(null,null);
            if (result.failed()>0 || result.maintenanceFailed()>0) throw new IllegalStateException("REPORT_DAILY_PARTIAL_FAILURE");
            return "published="+result.published()+",deferred="+result.deferred()+",maintenanceFailed="+result.maintenanceFailed();
        });
    }
    @Override public JobResult run(JobInvocation invocation) {
        try {
            var result=service.runDaily(invocation==null?null:invocation.runId(),invocation==null?null:invocation.bizDate());
            String detail="published="+result.published()+",failed="+result.failed()+",deferred="+result.deferred()+",maintenanceFailed="+result.maintenanceFailed();
            return result.failed()==0 && result.maintenanceFailed()==0?JobResult.ok(detail):JobResult.failed(detail,"REPORT_DAILY_PARTIAL_FAILURE");
        } catch (RuntimeException ex) { return JobResult.failed("REPORT_DAILY_FAILED","REPORT_DAILY_FAILED"); }
    }
    @Bean public JobDeclaration foodReportDailyDeclaration() {
        return new JobDeclaration(NAME,"餐饮经营日报","每天一次生成已结束营业日，统一恢复漏跑、失败及变更日期；不执行支付或退款。",
                "shop-app","0 30 4 * * *",true,6000,7200,true,false);
    }
}
