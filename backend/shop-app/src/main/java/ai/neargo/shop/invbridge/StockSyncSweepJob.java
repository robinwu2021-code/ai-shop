package ai.neargo.shop.invbridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockSyncMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每晚兜底写回（TDD-商品纳入进销存开关 §6.1）：开了同步的每家店，接入进销存的每个 SKU 按同一逻辑算一遍。
 *
 * <p>补的是漏掉的事件 —— 投递失败转了 FAILED 的、镜像迟迟没追平被放弃的、规则改动时本店还没开同步的。
 * 幂等键 {@code SWEEP:日期}：同一晚重跑不重复写。只改商城线上可卖，从不改进销存实存。
 */
@Component
@ConditionalOnProperty(name = { "shop.job.enabled", "shop.inventory.enabled" }, havingValue = "true")
public class StockSyncSweepJob implements JobHandler {

    private final InventoryWritebackService writeback;
    private final StoreStockSyncMapper syncMapper;
    private final JobSupport jobs;

    public StockSyncSweepJob(InventoryWritebackService writeback, StoreStockSyncMapper syncMapper, JobSupport jobs) {
        this.writeback = writeback;
        this.syncMapper = syncMapper;
        this.jobs = jobs;
    }

    /** 每天 04:10 —— 排在对账（03:40）与空壳物料归档（03:50）之后 */
    @Scheduled(cron = "${shop.job.stock-sync-sweep.cron:0 10 4 * * *}")
    @SchedulerLock(name = "stock-sync-sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void scan() {
        jobs.run("stock-sync-sweep", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "stock-sync-sweep";
    }

    @Bean
    public JobDeclaration stockSyncSweepDeclaration() {
        return JobDeclaration.daily("stock-sync-sweep", "库存同步每晚兜底",
                "开了库存同步的门店，按线上可售规则把商城可卖再对齐一遍，补白天漏掉的写回。只改商城，不改进销存实存",
                "shop-app", "0 10 4 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        String ref = "SWEEP:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        List<PrdStoreStockSync> stores = syncMapper.selectList(Wrappers.<PrdStoreStockSync>lambdaQuery()
                .eq(PrdStoreStockSync::getEnabled, 1));
        int skus = 0;
        int failed = 0;
        for (PrdStoreStockSync s : stores) {
            try {
                skus += writeback.syncStore(s.getEntityNo(), s.getStoreNo(), ref, true).size();
            } catch (RuntimeException e) {
                // 一家店失败（多半是镜像还没追平）不挡别的店；明晚再来
                failed++;
            }
        }
        String detail = "stores=" + stores.size() + " skus=" + skus + " failed=" + failed;
        return failed == 0 ? JobResult.ok(detail) : JobResult.failed(detail, "STORE_FAILED");
    }
}
