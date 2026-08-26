package ai.neargo.job.worker;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 定时任务调度器 —— **独立进程，不含任何业务代码**。
 *
 * <p>它只做两件事：<b>按点发起、如实记录</b>。任务体在业务系统里，它只知道名字。
 *
 * <p><b>为什么不是 Web 应用</b>：运营端直读 job 库、不与本进程通信。
 * 没有人调它，它就不需要监听端口。健康状况看 {@code job_run} 表 ——
 * worker 挂了的时候页面照样显示「最后一次跑是 2 小时前」，
 * 而那正是最需要看的时刻；若页面要向 worker 要数据，worker 一挂页面就是空白，
 * 等于把最关键的那次故障变成了盲区。
 *
 * <p><b>进程为什么不会立刻退出</b>：{@code ThreadPoolTaskScheduler} 起的是非守护线程，
 * 只要注册表还持有它，JVM 就不会退。不需要额外的 keep-alive。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JobApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(JobApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
