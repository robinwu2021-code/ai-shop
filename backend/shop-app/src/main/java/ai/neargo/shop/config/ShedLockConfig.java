package ai.neargo.shop.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 分布式锁的提供方。**从 {@code SchedulingConfig} 里搬出来的**。
 *
 * <p>搬的理由：那个类挂着 {@code @Profile("worker")}，于是锁只在进程内调度形态下存在。
 * 而任务已经改由独立调度器经 {@code /internal/job/*&#47;run} 打进来 ——
 * 那条路跑在 {@code api,ops} 下，<b>拿不到锁，也就没有任何并发保护</b>。
 * 锁的可用性不该跟着「谁来触发」变。
 *
 * <p>无条件装配：它只是一个 bean，不启动任何线程、不占任何资源，
 * 而 {@code shedlock} 表本来就在。
 */
@Configuration
public class ShedLockConfig {

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

    /** 编程式加锁的入口。{@code @SchedulerLock} 是注解式，内部端点用不上。 */
    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
