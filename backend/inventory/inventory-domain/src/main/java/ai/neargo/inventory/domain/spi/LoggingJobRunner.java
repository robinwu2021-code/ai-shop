package ai.neargo.inventory.domain.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * {@link InventoryJobRunner} 的兜底实现：调用方没有提供运行记录时用它。
 *
 * <p>只吞异常、不丢痕迹：失败一律打 WARN（带堆栈）。吞掉是因为 {@code @Scheduled} 抛出的异常
 * 只会被 Spring 打一行 ERROR、下一轮照跑 —— 那样连「失败过几次」都数不出来。
 */
public class LoggingJobRunner implements InventoryJobRunner {

    private static final Logger log = LoggerFactory.getLogger(LoggingJobRunner.class);

    @Override
    public void run(String jobName, Supplier<String> body) {
        try {
            String detail = body.get();
            if (detail != null) {
                log.info("[inventory-job] {} —— {}", jobName, detail);
            }
        } catch (RuntimeException e) {
            log.warn("[inventory-job] {} 失败", jobName, e);
        }
    }
}
