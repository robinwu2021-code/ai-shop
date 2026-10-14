package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.spi.trade.WxShippingPort;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信发货信息录入的<b>补报任务</b>：把上报台账里 PENDING 的行真正发出去。
 *
 * <h2>为什么上报要靠任务，而不是发货那一刻直接调</h2>
 * 上报是跨网络的副作用，而<b>失败的代价是这笔钱结不出来</b> ——
 * 微信对实物电商类小程序默认把货款<b>冻结</b>，录入发货信息、用户确认收货
 * （或到期自动确认）之后才进入结算。没报的单，钱一直冻着，
 * 用户端毫无感知，商家几天后才发现。
 *
 * <p>所以状态迁移那一刻只落台账（{@link WxShippingUploadService#enqueue}），
 * 真正的调用由这个任务反复来，直到成功或到重试上限。
 *
 * <h2>三种结果分开数</h2>
 * 「成功」「失败」「材料不齐发不出去」是三件不同的事，混成一个数就看不出问题在哪。
 * 第三类现在主要是 V344 之前发的<b>存量快递单</b>（没有快递公司这一列，补不出来）
 * 与缺 openid 的老流水（见 {@link WxShippingUploadResolver#resolve}）。
 * 混进「失败」里的话，运营看到的是一片红，而真正的通道失败被埋在里面。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class WxShippingUploadJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(WxShippingUploadJob.class);

    @Value("${shop.job.wx-shipping-upload.limit:200}")
    private int limit;

    private final WxShippingUploadService uploads;
    private final WxShippingUploadResolver resolver;
    /**
     * 用 ObjectProvider 而不是直接注入：{@code WxShippingPort} 由 stub
     * （{@code matchIfMissing=true}）保证恒存在，但 ConditionalBeanWiringTest 是静态分析，
     * 看不到 matchIfMissing —— 直接注入会被误判成「依赖了开关更严的 bean」。
     * 延迟解析既避开这个误报，也没有加载顺序风险（运行时那一刻 stub/真通道必有其一）。
     */
    private final ObjectProvider<WxShippingPort> shippingProvider;
    private final JobSupport jobs;

    public WxShippingUploadJob(WxShippingUploadService uploads, WxShippingUploadResolver resolver,
                               ObjectProvider<WxShippingPort> shippingProvider, JobSupport jobs) {
        this.uploads = uploads;
        this.resolver = resolver;
        this.shippingProvider = shippingProvider;
        this.jobs = jobs;
    }

    /*
     * 每 10 分钟一轮。比对账那几个任务密：发货到「48 小时未发货」告警之间的余量
     * 是以小时计的，一小时一轮的话一次停摆就吃掉大半余量。
     */
    @Scheduled(cron = "${shop.job.wx-shipping-upload.cron:0 */10 * * * *}")
    @SchedulerLock(name = "wx-shipping-upload", lockAtLeastFor = "PT1M", lockAtMostFor = "PT9M")
    public void scan() {
        jobs.run("wx-shipping-upload", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "wx-shipping-upload";
    }

    @Bean
    public JobDeclaration wxShippingUploadDeclaration() {
        return new JobDeclaration("wx-shipping-upload", "微信发货信息录入补报",
                "把上报台账里待上报的单发给微信。没报的单货款会一直冻结在微信那边，"
                        + "用户端毫无感知。材料不齐的（例如快递单缺快递公司）单独计数并留在待上报，"
                        + "补齐那天自己会报上去",
                "shop-app", "0 */10 * * * *", true,
                540, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        WxShippingPort shipping = shippingProvider.getObject();
        if (!shipping.enabled()) {
            /*
             * 桩模式。**这不是「跑成功了」** —— 说清楚，否则运营页面上一行绿色的「成功」
             * 会让人以为发货都报上去了，而实际上一条都没发。
             */
            return JobResult.ok("通道未启用（shop.wx.shipping.stub），本轮不发任何请求");
        }

        List<TrdShippingUpload> rows = uploads.pending(limit);
        if (rows.isEmpty()) {
            return JobResult.ok("没有待上报的单");
        }

        int ok = 0;
        int failed = 0;
        Map<String, Integer> blocked = new LinkedHashMap<>();
        for (TrdShippingUpload row : rows) {
            WxShippingUploadResolver.Material m;
            try {
                m = resolver.resolve(row);
            } catch (RuntimeException e) {
                log.error("[wxship] 订单 {} 取上报材料失败", row.getOrderNo(), e);
                failed++;
                continue;
            }
            if (!m.ready()) {
                uploads.blocked(row, m.missReason());
                blocked.merge(m.missReason(), 1, Integer::sum);
                continue;
            }
            boolean settled = uploads.upload(row, m.itemDesc(),
                    m.trackingNo(), m.expressCompany(), m.payerOpenid());
            if (settled && TrdShippingUpload.SUCCESS.equals(
                    uploads.statusOf(row.getId()))) {
                ok++;
            } else if (settled) {
                failed++;
            }
        }

        /*
         * **对照量先判。**「一笔都没报成功」与「一笔都没扫到」在数字上长得一样，
         * 而前者才是该红的那种。扫到了却一笔没成，就把原因摊开说。
         */
        StringBuilder sb = new StringBuilder();
        sb.append("扫到 ").append(rows.size()).append(" 笔：成功 ").append(ok)
                .append("，失败 ").append(failed);
        if (!blocked.isEmpty()) {
            int n = blocked.values().stream().mapToInt(Integer::intValue).sum();
            sb.append("，材料不齐 ").append(n).append("（仍留在待上报）");
            blocked.forEach((reason, cnt) -> sb.append("\n  · ").append(cnt).append(" 笔：").append(reason));
        }
        String detail = sb.toString();
        if (ok == 0 && failed > 0) {
            log.error("[wxship] 本轮一笔都没报成功 —— **这些单的钱结不出来**。{}", detail);
            return JobResult.failed(detail, "一笔都没报成功：这些单的货款会冻在微信那边");
        }
        return JobResult.ok(detail);
    }
}
