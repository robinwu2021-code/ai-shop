package ai.neargo.shop.job;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内的任务体索引：{@code handlerName → JobHandler}。
 *
 * <p>各域把自己的 {@link JobHandler} 交出来（Spring 收集全部实现），这里按名字建索引。
 * 调度器只知道名字，任务体留在域里 —— 这是「发布不打断任务」成立的前提：
 * worker 里若编译进业务代码，业务发版后它不重启就跑着上一版逻辑。
 *
 * <h2>重名当场启动失败</h2>
 * 两个 handler 同名时，<b>其中一个会静默地永远不被执行</b> ——
 * 而它的任务在运营页面上照常显示、照常有下次执行时间。
 * 这种缺陷不会报错，只会让某件事从某天起再也没发生过。
 */
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, JobHandler> byName = new LinkedHashMap<>();
    private final Map<String, JobDeclaration> declarations = new LinkedHashMap<>();

    public JobHandlerRegistry(List<JobHandler> handlers, List<JobDeclaration> declared) {
        for (JobHandler h : handlers) {
            JobHandler prev = byName.put(h.name(), h);
            if (prev != null) {
                throw new IllegalStateException(
                        "定时任务 handler 重名：%s（%s 与 %s）—— 其中一个会静默地永远不执行"
                                .formatted(h.name(), prev.getClass().getName(), h.getClass().getName()));
            }
        }
        for (JobDeclaration d : declared) {
            if (declarations.put(d.handlerName(), d) != null) {
                throw new IllegalStateException("任务声明重名：" + d.handlerName());
            }
            if (!byName.containsKey(d.handlerName())) {
                // 声明了却没有实现：那个任务会被 upsert 进表、在页面上出现，然后永远 404。
                // 启动时炸掉，比让运营对着一个点不动的任务发愁强
                throw new IllegalStateException(
                        "任务 " + d.handlerName() + " 有声明但没有 JobHandler 实现");
            }
        }
        log.info("定时任务 handler 就绪：{} 个", byName.size());
    }

    public Optional<JobHandler> find(String handlerName) {
        return Optional.ofNullable(byName.get(handlerName));
    }

    /** 全部声明，worker 启动时据此 upsert 进 {@code job_definition}。 */
    public List<JobDeclaration> declarations() {
        return new ArrayList<>(declarations.values());
    }

    public int size() {
        return byName.size();
    }
}
