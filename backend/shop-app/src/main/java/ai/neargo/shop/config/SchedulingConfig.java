package ai.neargo.shop.config;

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 定时任务开关。**只在 worker 部署开启**（S8 的三种起法）。
 *
 * <p>不加这个 profile 限制的话，api 部署的每个实例都会各跑一遍同样的任务——
 * 多实例下就是重复执行。资质扫描重复执行只是多写几条审计，
 * 但结算、关单这类任务重复执行会真的出问题，所以从第一个任务起就把闸设在这里。
 *
 * <p>api 与 ops 部署上连 {@code @Scheduled} 的解析都不会发生，
 * 不需要靠「任务内部判断自己该不该跑」这种容易漏的写法。
 *
 * <p><b>但这个闸只挡住「别的部署形态」，挡不住「worker 起了两个实例」。</b>
 * 扩容是运维在容量吃紧时做的动作，不会回来问代码准备好没有 ——
 * 所以锁要在第一个实例上线时就在，而不是等扩容前补。
 * ShedLock 由 {@link EnableSchedulerLock} 接管，各任务用
 * {@code @SchedulerLock} 声明自己的锁名与持锁时长。
 */
@Profile("worker")
@Configuration
@EnableScheduling
// defaultLockAtMostFor 是兜底：任务所在实例崩了、没来得及释放锁时，
// 最多锁这么久就允许别人接手。**它不该被当成默认值用** —— 每个任务
// 自己声明 lockAtMostFor，因为「跑多久算异常」只有任务自己知道。
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

    /**
     * 锁存在数据库里，而不是 Redis。
     *
     * <p>本仓已经有 Redis（会话），用它做锁也可以。选库的理由是
     * <b>锁和被锁的数据在同一个事务边界内</b>：积分转正改的是库里的余额行，
     * 锁也在库里 —— 库挂了两者一起不可用，不会出现「锁还在、库没了」
     * 这种半可用状态下的重复执行。
     *
     * <p>{@code usingDbTime()}：用数据库的时钟判断锁是否过期，而不是各实例自己的。
     * 不加这句的话，两台机器差几秒就可能同时认为锁已过期。
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
