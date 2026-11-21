package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.paybridge.WxWaybillBindService.BindResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 微信物流插件 token 绑定轮询（TDD-物流轨迹多渠道 §2.3）。
 *
 * <p>把在途、小程序可用、还没拿到 {@code waybill_token} 的运单，逐个去微信换 token 落库，
 * 之后小程序订单详情点「查看物流」就能打开微信全屏物流页。换不到的保持自建地图。
 *
 * <h2>为什么独立一个 job、错开轨迹轮询</h2>
 * 这是**展示渠道**那条轴（微信怎么呈现），与**数据源**轴（{@code logistics-trace} 去快递100 拉节点）
 * 正交。两者对同一批在途单各做各的：拆成两个 job，一个挂了不拖累另一个，cron 也错开
 * （轨迹在 0/30，绑定在 5/35），不在同一分钟挤外部调用。
 *
 * <h2>换不到不是「跑失败了」</h2>
 * 微信 {@code trace_waybill} 常见换不到：运单刚发货微信还没有（{@code 9300559}）、
 * 线下付款单没有 trans_id。这类**如实计 attempted 不计 prepared**，下一轮再试，不编造成功。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class WxWaybillBindJob implements JobHandler {

    private final WxWaybillBindService bind;
    private final JobSupport jobs;

    /** 一轮最多备多少单。构造注入（不是字段 {@code @Value}）——测试手工 new 时必须显式给值。 */
    private final int limit;

    public WxWaybillBindJob(WxWaybillBindService bind, JobSupport jobs,
                            @Value("${shop.job.wx-waybill-bind.limit:200}") int limit) {
        this.bind = bind;
        this.jobs = jobs;
        this.limit = limit;
    }

    // 30 分钟一轮，错开轨迹轮询（后者在 0/30，这个在 5/35）。
    @Scheduled(cron = "${shop.job.wx-waybill-bind.cron:0 5,35 * * * *}")
    @SchedulerLock(name = "wx-waybill-bind", lockAtLeastFor = "PT1M", lockAtMostFor = "PT9M")
    public void poll() {
        jobs.run("wx-waybill-bind", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "wx-waybill-bind";
    }

    @Bean
    public JobDeclaration wxWaybillBindDeclaration() {
        return new JobDeclaration("wx-waybill-bind", "微信物流插件 token 绑定",
                "把在途、小程序单去微信 trace_waybill 换 waybill_token 落库，"
                        + "之后小程序订单详情能打开微信全屏物流页。换不到（运单微信还没有/无 trans_id）的保持自建地图",
                "shop-app", "0 5,35 * * * *", true,
                540, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        BindResult r = bind.bindPending(limit);
        if (r.attempted() == 0) {
            return JobResult.ok("没有待备的在途运单");
        }
        return JobResult.ok("待备 " + r.attempted() + " 单，换到微信 token " + r.prepared() + " 单");
    }
}
