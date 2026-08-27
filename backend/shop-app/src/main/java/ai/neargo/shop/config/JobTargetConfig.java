package ai.neargo.shop.config;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.shop.job.JobHandlerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * <b>业务系统作为「任务目标」</b>：手上有任务体，等着被调度器调。
 *
 * <p>这与「进程内跑调度器」（{@link InProcessJobConfig}）是两件事，
 * 而它们曾经写在一起 —— 注册表的 bean 定义在那个 {@code @Profile("worker")}
 * 的类里。后果是<b>独立调度器一上线，业务系统就起不来</b>：
 * 生产跑的是 {@code api,ops}，容器里根本没有 {@link JobHandlerRegistry}，
 * 而 {@code /internal/job/**} 的端点要它。2026-08-27 线上撞到，回滚一次。
 *
 * <p>拆开之后两个角色各自成立：
 * <ul>
 *   <li>{@code api,ops} + {@code shop.job.enabled=true} → 只当目标，调度器在别的进程</li>
 *   <li>再加 {@code worker} profile → 顺便自己调度（拆进程之前的过渡形态）</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class JobTargetConfig {

    /**
     * 收集全部任务体并按名字建索引。<b>重名或「有声明没实现」当场启动失败</b> ——
     * 见 {@link JobHandlerRegistry} 的类注释。
     */
    @Bean
    JobHandlerRegistry jobHandlerRegistry(List<JobHandler> handlers,
                                          List<JobDeclaration> declarations) {
        return new JobHandlerRegistry(handlers, declarations);
    }
}
