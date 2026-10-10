package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.paybridge.WxConfirmReceiveService.NotifyResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 签收 → 提醒买家确认收货（批 A）。逻辑全在 {@link WxConfirmReceiveService}。
 *
 * <p><b>为什么要单独一个作业而不是跟在轨迹轮询后面</b>：轨迹轮询在履约域，
 * 它看不到支付台账，也不该为了调微信去依赖交易域。两件事分开，
 * 一个坏了另一个照跑 —— 轨迹查不到不该连带让已经签收的单也提醒不出去。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class WxConfirmReceiveJob implements JobHandler {

    private final WxConfirmReceiveService confirm;
    private final JobSupport jobs;

    private final int limit;

    public WxConfirmReceiveJob(WxConfirmReceiveService confirm, JobSupport jobs,
                               @Value("${shop.job.wx-confirm-receive.limit:200}") int limit) {
        this.confirm = confirm;
        this.jobs = jobs;
        this.limit = limit;
    }

    // 30 分钟一轮，再错开一档：轨迹轮询 0/30、换 token 5/35、这个 10/40。
    // 三者有先后依赖（先查到签收才谈得上提醒），错开能让同一次签收在一小时内走完整条链。
    @Scheduled(cron = "${shop.job.wx-confirm-receive.cron:0 10,40 * * * *}")
    @SchedulerLock(name = "wx-confirm-receive", lockAtLeastFor = "PT1M", lockAtMostFor = "PT9M")
    public void poll() {
        jobs.run("wx-confirm-receive", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "wx-confirm-receive";
    }

    @Bean
    public JobDeclaration wxConfirmReceiveDeclaration() {
        return new JobDeclaration("wx-confirm-receive", "微信确认收货提醒",
                "承运商回传签收后，调微信「确认收货提醒」让买家去点确认 —— 买家确认（或微信到期自动确认）"
                        + "之后这笔钱才进入结算。微信不会把签收回调给开发者，所以这一步只能我们自己触发。"
                        + "每个支付单仅提醒一次，仅物流快递单",
                "shop-app", "0 10,40 * * * *", true,
                540, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        NotifyResult r = confirm.notifyPending(limit);
        if (r.scanned() == 0) {
            return JobResult.ok("没有已签收待提醒的运单");
        }
        return JobResult.ok("已签收 " + r.scanned() + " 单，发出确认收货提醒 " + r.notified() + " 单");
    }
}
