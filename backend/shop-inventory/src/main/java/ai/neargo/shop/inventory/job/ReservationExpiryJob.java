package ai.neargo.shop.inventory.job;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收到期未确认的预留。
 *
 * <p><b>跨进程之后，兜底必须在本领域内</b>：调用方可能永远不回来（进程挂了、网络断了、
 * 客户的 OMS 有 bug）。进程内时代靠平台的关单任务兜着，
 * 而独立交付时那个任务根本不存在 —— 那时这条就是唯一的回收路径。
 *
 * <p>不回收的后果不是数据难看，是**别人的可用数量白白少掉**：
 * 一笔没人管的预留会一直占着那几件货，而商品在 C 端显示售罄。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@ConditionalOnInventory
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    /** 一轮的上界。预留积压时不至于把这一轮拖成长事务 —— 剩下的下一轮再来。 */
    private static final int BATCH = 200;

    private final ReservationService reservations;
    private final JobSupport jobs;

    public ReservationExpiryJob(ReservationService reservations, JobSupport jobs) {
        this.reservations = reservations;
        this.jobs = jobs;
    }

    /** 每分钟一次：预留的 TTL 以分钟计，扫得比它疏就等于 TTL 名存实亡。 */
    @Scheduled(cron = "${shop.job.inv-reservation-expiry.cron:0 * * * * *}")
    @SchedulerLock(name = "inv-reservation-expiry", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void run() {
        jobs.run("inv-reservation-expiry", this::expireOnce);
    }

    /**
     * 回收一轮。**两条触发路径共用它** —— 见 {@code InvOutboxDispatchJob.dispatchOnce} 的注释：
     * `@Scheduled` 那条在生产上根本不解析（`@EnableScheduling` 挂 worker profile）。
     *
     * <p>这个任务是三个里**最危险的一个**：不跑的话，未付款订单的预留永不回收，
     * 那批货被永久占着，而商家看到的是「有货卖不出去」。今天线上 0 预留，所以还没出事。
     */
    public String expireOnce() {
        int n = reservations.expireOverdue(BATCH);
        if (n > 0) {
            log.info("回收到期预留 {} 条", n);
        }
        /*
         * **把剩下多少也报出来**（INV-P5 的落点）。
         *
         * 回收是分批的（一轮 200 条），所以「本轮回收了 N 条」答不出「还积着多少」。
         * 而积压**持续不降**才是这个任务出问题的信号 —— 那批货被永久占着，
         * 商家看到的是「有货卖不出去」，而任何地方都不会报错。
         *
         * 报在 detail 里而不是做一张运营端的表：运营已经在 `/jobs` 上看这一行，
         * 而一张恒为空的表没人会去点开（线上 `inv_reservation` 今天是 0 行）。
         * 有积压时它自己会出现在「最后一次」那一列上。
         */
        int left = reservations.countOverdue();
        return left > 0 ? "expired=" + n + " 积压=" + left : "expired=" + n;
    }
}
