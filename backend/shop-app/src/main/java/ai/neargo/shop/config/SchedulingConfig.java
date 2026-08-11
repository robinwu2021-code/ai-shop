package ai.neargo.shop.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关。**只在 worker 部署开启**（S8 的三种起法）。
 *
 * <p>不加这个 profile 限制的话，api 部署的每个实例都会各跑一遍同样的任务——
 * 多实例下就是重复执行。资质扫描重复执行只是多写几条审计，
 * 但结算、关单这类任务重复执行会真的出问题，所以从第一个任务起就把闸设在这里。
 *
 * <p>api 与 ops 部署上连 {@code @Scheduled} 的解析都不会发生，
 * 不需要靠「任务内部判断自己该不该跑」这种容易漏的写法。
 */
@Profile("worker")
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
