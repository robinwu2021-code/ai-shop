package ai.neargo.shop.portal.internal;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.job.api.JobStatus;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.job.JobHandlerRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 独立调度器调业务系统的两个内部口。**任务体留在这里，调度器只知道名字。**
 *
 * <p>这是「发布不打断任务」成立的前提：worker 里若编译进业务代码，
 * 业务发版后它不重启就跑着上一版逻辑 —— 而「不重启 worker」正是做这整件事的理由。
 *
 * <h2>四条硬要求</h2>
 * <ol>
 *   <li><b>绑内网</b>：nginx 不反代 {@code /internal/**}，worker 走 {@code 127.0.0.1:8081}</li>
 *   <li><b>共享密钥</b>，不是用户令牌。这个口<b>不建 {@code BizContext}、不认任何用户身份</b> ——
 *       handler 里需要「谁操作的」时写死系统账号</li>
 *   <li><b>不记 body</b>：{@code params} 将来可能带业务标识，{@code detail} 同理。
 *       调度链路的日志不该成为一个额外的数据出口</li>
 *   <li><b>密钥没配就一律 401</b>。装不装跟着 {@code shop.job.enabled} 走，
 *       <b>不跟着密钥走</b> —— 跟着密钥的话，漏配时端点整个消失、调度器拿 404、
 *       任务全变 HandlerNotFound，症状指向「代码里没这个任务」，离真因隔了两层</li>
 * </ol>
 */
@RestController
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class JobHandlerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerEndpoint.class);

    private final JobHandlerRegistry handlers;
    private final String token;

    public JobHandlerEndpoint(JobHandlerRegistry handlers,
                              @Value("${shop.job.internal-token:}") String token) {
        this.handlers = handlers;
        this.token = token;
    }

    /**
     * 代码里声明了哪些任务。**调度器启动与每轮轮询都来问这里。**
     *
     * <p>为什么是「问」而不是「调度器自己知道」：声明的源头在业务代码里
     * （中文名、默认 cron、属于哪个模块，只有代码知道），
     * 而业务系统按设计碰不到 job 库 —— 它连连接串都没有。只剩这一条路。
     */
    @GetMapping("/internal/job/declarations")
    public ResponseEntity<List<JobDeclaration>> declarations(
            @RequestHeader(value = "X-Job-Token", required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(handlers.declarations());
    }

    /**
     * 跑一轮。**同步**：业务侧跑完把结果放在响应里，调度器收到就写自己的库。
     *
     * <p>不用 202 异步：那需要一条回调链路，外加一个「回调丢了导致 run 永远卡在
     * RUNNING」的失败态。调用量实测约 4640 次/天（每 19 秒一次），同步毫无压力。
     */
    @PostMapping("/internal/job/{handlerName}/run")
    public ResponseEntity<RunResp> run(@PathVariable String handlerName,
                                       @RequestHeader(value = "X-Job-Token", required = false) String given,
                                       @RequestBody(required = false) RunReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<JobHandler> handler = handlers.find(handlerName);
        if (handler.isEmpty()) {
            // 代码里删了但 job_definition 里还留着。调度器会把它标成 missing，
            // 而不是一直「跑」却什么也不做
            log.warn("调度器要跑一个不存在的 handler：{}", handlerName);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        JobInvocation in = new JobInvocation(
                req == null || req.runId() == null ? "-" : req.runId(),
                triggerOf(req), bizDateOf(req),
                req == null || req.params() == null ? Map.of() : req.params());
        try {
            JobResult r = runLocked(handlerName, handler.get(), in);
            if (r.status() == JobStatus.SKIPPED) {
                // 锁没抢到。**409 而不是 200+SKIPPED** —— 调度器据此不计入连续失败，
                // 而 200 会让「正常的并发保护」和「跑成了」在 HTTP 层长得一样
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new RunResp(r.status().name(), r.detail(), r.error()));
            }
            return ResponseEntity.ok(new RunResp(r.status().name(), r.detail(), r.error()));
        } catch (RuntimeException e) {
            /*
             * **不往外抛。** 抛出去会变成 500，而调度器把 5xx 记成 FAILED ——
             * 结果一样，但错误信息里只有 "Http500"，看不出是哪个异常。
             * 这里把异常类名放进 error，排查时第一眼就能看到。
             */
            log.error("任务 {} 抛异常", handlerName, e);
            return ResponseEntity.ok(new RunResp(JobStatus.FAILED.name(),
                    "任务抛异常", e.getClass().getSimpleName()));
        }
    }

    /**
     * 带锁执行。<b>锁名就是 handler 名</b> —— 与旧的 {@code @SchedulerLock} 同名，
     * 于是新旧两条触发路径争的是 {@code shedlock} 表里的同一行。
     *
     * <h2>为什么非加不可</h2>
     * <p>旧的锁挂在 {@code @Scheduled} 的方法上，而调度器打进来的是
     * {@code JobHandler.run()} —— <b>锁完全不参与</b>。此前不出事只靠三个巧合同时成立：
     * {@code worker} profile 没开、业务系统单实例、调度器单实例。
     * 任何一个变了，任务就会双跑，而 ShedLock 拦不住它没参与的那条路。
     *
     * <p>连带效果：{@code SKIPPED}/409 这条契约<b>此前线上根本走不到</b>
     * —— 协议两边都写了、都有测试，但没有任何东西会产生它。
     *
     * <h2>持锁时长取声明里的值</h2>
     * <p>不取一个统一的默认值：「跑多久算异常」只有任务自己知道。
     * 声明里没有（理论上不会发生，注册表启动时就校验过）才退到 30 分钟。
     *
     * <p>{@code lockAtLeastFor} 给 0：那个参数防的是多实例间时钟漂移导致的抢跑，
     * 而这里的调用方是<b>单一调度器</b>，它自己不会在同一时刻发两次。
     * 给非零反而会让手动触发在 cron 刚跑完时被无谓地拒掉。
     */
    private JobResult runLocked(String handlerName, JobHandler handler, JobInvocation in) {
        Duration lockAtMost = handlers.declarations().stream()
                .filter(d -> d.handlerName().equals(handlerName))
                .findFirst()
                .map(d -> Duration.ofSeconds(d.lockAtMostSec()))
                .orElse(Duration.ofMinutes(30));
        LockingTaskExecutor.TaskResult<JobResult> res;
        try {
            res = locks.executeWithLock(
                    (LockingTaskExecutor.TaskWithResult<JobResult>) () -> handler.run(in),
                    new LockConfiguration(Instant.now(), handlerName, lockAtMost, Duration.ZERO));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        // 没抢到 = 上一轮还在跑。**不是故障**，交给上面转成 409
        return res.wasExecuted() ? res.getResult() : JobResult.skipped();
    }

    /**
     * 常量时间比较。**不用 equals** —— 它一发现不同就返回，
     * 比较耗时会随着「猜对了几个字符」变化，那是一条可测量的旁路。
     */
    private boolean authorized(String given) {
        if (given == null || token == null || token.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                given.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static TriggerType triggerOf(RunReq req) {
        if (req == null || req.triggerType() == null) {
            return TriggerType.CRON;
        }
        try {
            return TriggerType.valueOf(req.triggerType());
        } catch (IllegalArgumentException e) {
            return TriggerType.CRON;
        }
    }

    /** 空串按 null 处理：调度器对不关心日期的任务发的就是空串。 */
    private static LocalDate bizDateOf(RunReq req) {
        String v = req == null ? null : req.bizDate();
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record RunReq(String runId, String triggerType, String bizDate, Map<String, String> params) {
    }

    public record RunResp(String status, String detail, String error) {
    }
}
