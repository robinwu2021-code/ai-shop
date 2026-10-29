package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.domain.spi.InventoryJobRunner;
import ai.neargo.shop.job.JobSupport;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 内嵌时进销存的定时任务记进商城的任务运行记录（{@code sys_job_run}），
 * 与改造前同一张表、同一个任务名 —— 运营端「任务运行」页照旧看得到这三个任务。
 */
@Component
public class ShopInventoryJobRunner implements InventoryJobRunner {

    private final JobSupport jobs;

    public ShopInventoryJobRunner(JobSupport jobs) {
        this.jobs = jobs;
    }

    @Override
    public void run(String jobName, Supplier<String> body) {
        jobs.run(jobName, body);
    }
}
