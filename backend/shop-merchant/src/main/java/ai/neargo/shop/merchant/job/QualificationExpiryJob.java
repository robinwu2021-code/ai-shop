package ai.neargo.shop.merchant.job;

import org.springframework.context.annotation.Bean;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 资质到期扫描。
 *
 * <p><b>这是「系统知道谁的证过期了」的那一半。</b>另一半在上架校验里
 * （{@code MerchantGoodsServiceImpl.requireCategoryAuthorized}）——两道防线针对不同时机：
 * 这里覆盖<b>已经在架的</b>商品，上架校验覆盖<b>正要上架的</b>。
 * 只有上架校验的话，证在商品上架之后才过期，那批货会一直卖下去。
 *
 * <p><b>只在 worker 部署跑</b>（S8 的三种起法）：批量任务与 API 抢同一个连接池时，
 * 扫描跑一轮能把下单接口拖到超时，而这种拖慢在监控上看起来像「数据库慢」。
 * api 与 ops 部署上这个 Bean 根本不存在，不需要靠开关关掉它。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class QualificationExpiryJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(QualificationExpiryJob.class);

    private final MerchantGovernService governService;
    private final AuditLogPort auditLogPort;

    private final JobSupport jobs;

    public QualificationExpiryJob(MerchantGovernService governService, AuditLogPort auditLogPort,
                                  JobSupport jobs) {
        this.governService = governService;
        this.auditLogPort = auditLogPort;
        this.jobs = jobs;
    }

    /**
     * 每天凌晨扫一次。
     *
     * <p>频率不必更高：资质到期是以天为单位的事，而上架那道防线是实时的，
     * 中间这点间隔漏不掉「正要上架」的情况。
     *
     * <p>扫描只把资质置为 {@code EXPIRED}，**不直接下架商品**——
     * 下架由上架校验那一侧承担（商家再次上架时被拦）。
     * 直接批量下架的风险是：商家可能已经续了证只是还没传新的，
     * 一夜之间整店下架而他毫不知情。**先让状态变成事实，再让人看见。**
     */
    @Scheduled(cron = "${shop.job.qualification-expiry.cron:0 10 3 * * *}")
    // 这条改的是商家状态、会导致整店下架 —— 重复执行本身幂等（已过期的再置一次还是过期），
    // 但它会重复写审计并重复触达商家。**通知发两遍，商家会以为出了两次问题。**
    @SchedulerLock(name = "qualification-expiry", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void scan() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("qualification-expiry", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "qualification-expiry";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration qualificationexpiryDeclaration() {
        return JobDeclaration.daily("qualification-expiry", "商家资质到期扫描",
                "把过期的商家资质置为 EXPIRED，并写一条审计。不跑的话过期资质仍能上架需资质的类目",
                "shop-merchant", "0 10 3 * * *");
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        Set<String> affected = governService.expireOverdueQualifications();
        if (affected.isEmpty()) {
            // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」
            return JobResult.ok(null);
        }
        log.warn("资质到期：{} 家商家的资质已置为过期 {}", affected.size(), affected);
        for (String merchantNo : affected) {
            // 写审计而不是只打日志：运营要能在后台看到「谁什么时候过期的」，
            // 而不是去翻服务器日志
            auditLogPort.record("QUALIFICATION_EXPIRED", merchantNo,
                    "资质到期，已置为 EXPIRED；该商家无法再上架需资质的类目");
        }
        return JobResult.ok(affected.size() + " 家商家的资质已过期");
    }
}
