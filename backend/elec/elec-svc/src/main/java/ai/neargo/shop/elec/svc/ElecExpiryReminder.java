package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.service.ElecSupplierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天一次：库存快到期的供应商发一条站内信。规则全在 {@link ElecSupplierService#remindExpiring}，这里只管按时叫它。
 *
 * <p><b>为什么不接 job 平台</b>：它的执行器在主系统一侧，调元器件要再开一个内部接口、再登记一种任务。
 * 这是「每天一次、失败了明天再来」的提醒，不涉及钱、不要重试编排。同一家同一周期的互斥在服务里
 * （条件更新 {@code expiry_reminded_at}），所以将来 elec-svc 扩成多实例也不会重复发。
 *
 * <p>时间由 {@code elec.expiry-remind-cron} 配（默认每天 9 点）；设成 {@code -} 关掉。
 */
@Component
public class ElecExpiryReminder {

    private static final Logger log = LoggerFactory.getLogger(ElecExpiryReminder.class);

    private final ElecSupplierService suppliers;

    public ElecExpiryReminder(ElecSupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @Scheduled(cron = "${elec.expiry-remind-cron:0 0 9 * * *}")
    public void run() {
        try {
            int n = suppliers.remindExpiring();
            log.info("库存到期提醒：这次提醒了 {} 家", n);
        } catch (RuntimeException e) {
            // 吞掉：定时任务里抛出去只会进日志，还会让 Spring 在某些配置下停掉这个任务
            log.warn("库存到期提醒这一轮失败，明天再来 {}", e.toString());
        }
    }
}
