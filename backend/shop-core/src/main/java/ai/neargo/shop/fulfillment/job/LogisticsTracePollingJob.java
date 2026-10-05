package ai.neargo.shop.fulfillment.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.fulfillment.service.LogisticsService.TraceRefreshResult;
import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 承运商轨迹轮询（TDD-圆通物流直连 Y3）。
 *
 * <p>把在途运单的<b>真实轨迹</b>拉回来、落进 {@code ful_shipment_trace}、据签收推进运单状态。
 * 这是 ADR-005 §5「一期不接承运商 API」被推迟的那块 —— 在它接上之前，运单状态只能靠
 * 订单状态粗略推导（发货=在途、确认收货=签收），买家点「查看物流」看不到中间节点。
 *
 * <h2>为什么是轮询而不是等发货那一刻查一次</h2>
 * 轨迹是<b>随时间变化</b>的：揽收→转运→派送→签收，发货那一刻只有第一条。
 * 圆通开放平台公开文档只有查询、没有推送（你账号若能开轨迹订阅推送，改回调更省额度，
 * 见 TDD §9），所以这一版按「最久没刷的优先」反复轮询在途单，签收后移出。
 *
 * <h2>缺凭据不是「跑成功了」</h2>
 * 默认 provider 是圆通，但凭据在服务器 env、要 IP 白名单。缺凭据时每一单都查不到
 * （{@code queried=0}），本任务<b>如实报「本轮一条都没查到」</b> —— 否则运营看到一行绿色
 * 会以为轨迹在动，而实际一条都没拉到（与 {@code WxShippingUploadJob} 的桩模式同一条教训）。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class LogisticsTracePollingJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(LogisticsTracePollingJob.class);

    private final LogisticsService logistics;
    private final JobSupport jobs;

    /** 一轮最多刷多少单。构造注入（不是 {@code @Value} 字段）—— 测试里手工 new 时必须显式给值。 */
    private final int limit;

    public LogisticsTracePollingJob(LogisticsService logistics, JobSupport jobs,
                                    @Value("${shop.job.logistics-trace.limit:300}") int limit) {
        this.logistics = logistics;
        this.jobs = jobs;
        this.limit = limit;
    }

    // 30 分钟一轮：轨迹节点的粒度是小时级，刷太密只是白查还吃圆通额度。
    @Scheduled(cron = "${shop.job.logistics-trace.cron:0 */30 * * * *}")
    @SchedulerLock(name = "logistics-trace", lockAtLeastFor = "PT1M", lockAtMostFor = "PT9M")
    public void poll() {
        jobs.run("logistics-trace", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "logistics-trace";
    }

    @Bean
    public JobDeclaration logisticsTraceDeclaration() {
        return new JobDeclaration("logistics-trace", "承运商轨迹轮询",
                "把在途运单的真实轨迹从承运商拉回来、落库、据签收推进运单状态。"
                        + "缺凭据（圆通客户密钥/IP 白名单未配）时一条都查不到，会如实报，不编造轨迹推进",
                "shop-core", "0 */30 * * * *", true,
                540, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        TraceRefreshResult r = logistics.refreshInTransitTraces(limit);
        if (r.scanned() == 0) {
            return JobResult.ok("没有在途运单");
        }
        String detail = "扫到在途 " + r.scanned() + " 单：查到轨迹 " + r.queried()
                + "，新增节点 " + r.appended() + " 单，推进状态 " + r.advanced()
                + "（其中签收 " + r.delivered() + "）";
        if (r.queried() == 0) {
            // **一单都没查到**：多半是圆通凭据/IP 白名单没配。说清楚，别让它看起来像「跑成功了」
            log.warn("[trace] 扫到 {} 单在途却一条轨迹都没查到 —— 多半是承运商凭据未配", r.scanned());
            return JobResult.ok(detail + "。一条都没查到：检查圆通凭据与 IP 白名单是否已配");
        }
        log.info("[trace] {}", detail);
        return JobResult.ok(detail);
    }
}
