package ai.neargo.shop.portal.ops;

import ai.neargo.job.store.JobDefinitionDao;
import ai.neargo.job.store.JobRunDao;
import ai.neargo.job.store.JobRunRow;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 运营端的服务端推送。**一条连接，多种事件。**
 *
 * <h2>为什么换掉轮询</h2>
 * <p>此前铃铛每 15 秒问一次未读数：一个人开着页面一天就是 2000 多次请求，
 * 其中绝大多数拿回一模一样的数字。而任务页更糟 —— 它<b>根本不刷新</b>，
 * 打开那一刻的状态会一直挂在屏幕上，「正在跑」跑完了也不会变。
 *
 * <h2>轮询没有消失，只是从 N 个浏览器挪到了 1 个服务端循环</h2>
 * <p><b>这一点必须说实话</b>：任务状态写在 job 库里，是<b>另一个进程</b>
 * （独立调度器）写的，业务系统没有任何办法被通知。所以这里仍然是轮询 ——
 * 区别在于原来是「每个开着页面的人 × 每 15 秒」，现在是「整个实例 × 每 3 秒」，
 * 且只在**内容变了**的时候才推给浏览器。
 *
 * <h2>为什么前端不能用 EventSource</h2>
 * <p>浏览器原生的 {@code EventSource} <b>不支持自定义请求头</b>，
 * 而运营端的会话在 {@code Authorization: Bearer} 里。把令牌塞进 query
 * 是不可接受的（它会进 nginx 访问日志、进浏览器历史、进 Referer）。
 * 所以前端用 {@code fetch} + {@code ReadableStream} 自己读 SSE 帧。
 *
 * <h2>推送线程里没有安全上下文</h2>
 * <p>第一版直接调 {@code messageService.unreadCount(RECEIVER_OPS)}，而那个方法
 * 内部用 {@code SecurityUtils.currentUserNo()} 按人过滤 —— 定时线程里没有
 * SecurityContext，每一轮都抛 {@code UnauthorizedException}。
 * <b>症状只是「页面不更新」</b>：连接正常、初次快照正常、之后再没有一帧，
 * 而异常被兜底 catch 住只进了日志。所以订阅那一刻就把 {@code staffNo} 记在连接上，
 * 之后按它查（{@code unreadCountOf}）。
 *
 * <h2>单实例假设</h2>
 * <p>连接注册表在进程内存里。多实例部署时每个实例只推给连到自己的那些人 ——
 * 这恰好是对的（每个实例各自轮询各自推），不需要跨实例广播。
 */
@RestController
@Profile("ops")
public class OpsStreamController {

    private static final Logger log = LoggerFactory.getLogger(OpsStreamController.class);

    /** 服务端轮询间隔。3 秒是「任务状态看起来是活的」与「库压力」之间的取舍。 */
    private static final Duration TICK = Duration.ofSeconds(3);

    /**
     * 心跳间隔。**没有它，连接会被中间的代理静默掐断**，
     * 而浏览器那侧表现为「页面不再更新」，没有任何报错。
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    /** 连接寿命。到点让浏览器重连一次 —— 长连接放着不管，异常状态会一直累积。 */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final MessageService messages;
    private final ObjectProvider<JobDefinitionDao> jobDefs;
    private final ObjectProvider<JobRunDao> jobRuns;
    /**
     * 一条连接。**未读数按人算，所以每条连接各记各的水位** ——
     * 共用一个 {@code lastUnread} 的话，A 的数字变了会把 B 的推送也一起触发，
     * 而 B 收到的是 A 的数。
     */
    private static final class Client {
        final SseEmitter emitter;
        final String staffNo;
        volatile long lastUnread = -1;

        Client(SseEmitter emitter, String staffNo) {
            this.emitter = emitter;
            this.staffNo = staffNo;
        }
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ops-stream");
                t.setDaemon(true);
                return t;
            });

    /**
     * 上一轮推出去的内容，用来判断「变了没有」。**不推没变的东西**是这套方案省下的全部开销。
     *
     * <p>存的是对象不是 JSON 字符串：{@code JobVO} 是 record，{@code equals} 现成的，
     * 而序列化交给 Spring 的消息转换器 —— <b>那样 SSE 与 REST 端点的格式逐字节相同</b>。
     * 自己 new 一个 ObjectMapper 的话，{@code LocalDateTime} 的写法就可能与
     * {@code GET /ops/jobs} 不一致，页面上同一个字段会出现两种样子。
     */
    private volatile List<OpsJobController.JobVO> lastJobs;
    private volatile long lastSentAt;

    public OpsStreamController(MessageService messages,
                               ObjectProvider<JobDefinitionDao> jobDefs,
                               ObjectProvider<JobRunDao> jobRuns) {
        this.messages = messages;
        this.jobDefs = jobDefs;
        this.jobRuns = jobRuns;
        ticker.scheduleWithFixedDelay(this::tick, TICK.toMillis(), TICK.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 订阅。<b>只要能登录就能订阅</b> —— 推的是未读数与任务状态，
     * 而任务详情本来就要 {@code system:job:read} 才看得到；
     * 这里再挂一道会让没有任务权限的人连不上流，连带丢掉未读数。
     */
    @GetMapping(value = "/ops/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // **在这里取身份**：只有请求线程上有 SecurityContext
        String staffNo = SecurityUtils.currentUserNo();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Client client = new Client(emitter, staffNo);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));
        clients.add(client);
        /*
         * **立刻推一次当前状态**，不等下一个 tick。
         * 不然页面打开后有最多 3 秒是空的，而用户会以为没加载出来。
         */
        try {
            client.lastUnread = readUnread(staffNo);
            emitter.send(SseEmitter.event().name("unread").data(client.lastUnread));
            List<OpsJobController.JobVO> jobs = readJobs();
            if (jobs != null) {
                emitter.send(SseEmitter.event().name("jobs")
                        .data(jobs, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | RuntimeException e) {
            clients.remove(client);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 一轮：读当前状态，只在变了（或该发心跳了）时推。 */
    private void tick() {
        if (clients.isEmpty()) {
            return;   // 没人看的时候一条 SQL 都不发
        }
        try {
            boolean changed = false;
            // 未读数按人算：每条连接各查各的，只推给数字变了的那一条
            for (Client c : clients) {
                long n = readUnread(c.staffNo);
                if (n != c.lastUnread) {
                    c.lastUnread = n;
                    send(c, "unread", n, null);
                    changed = true;
                }
            }
            // 任务快照三端共用，一次查、一次广播
            List<OpsJobController.JobVO> jobs = readJobs();
            if (jobs != null && !jobs.equals(lastJobs)) {
                lastJobs = jobs;
                broadcast("jobs", jobs, MediaType.APPLICATION_JSON);
                changed = true;
            }
            long now = System.currentTimeMillis();
            if (changed) {
                lastSentAt = now;
            } else if (now - lastSentAt > HEARTBEAT.toMillis()) {
                broadcast("ping", "1", null);
                lastSentAt = now;
            }
        } catch (RuntimeException e) {
            // 一轮失败不能让整个推送线程死掉 —— 死了之后页面永远停在最后一次，
            // 而没有任何地方会说它停了
            log.warn("推送轮询失败，本轮跳过 异常={}", e.getClass().getSimpleName());
        }
    }

    private void broadcast(String event, Object data, MediaType type) {
        for (Client c : clients) {
            send(c, event, data, type);
        }
    }

    private void send(Client c, String event, Object data, MediaType type) {
        try {
            c.emitter.send(type == null
                    ? SseEmitter.event().name(event).data(data)
                    : SseEmitter.event().name(event).data(data, type));
        } catch (IOException | IllegalStateException e) {
            // 对端关了页面。**这是常态，不是错误** —— 不打 WARN，否则日志会被刷满
            clients.remove(c);
        }
    }

    private long readUnread(String staffNo) {
        return messages.unreadCountOf(MsgMessage.RECEIVER_OPS, staffNo);
    }

    /** 任务快照。{@code shop.job.enabled=false} 时返回 null —— 那种部署没有任务页。 */
    private List<OpsJobController.JobVO> readJobs() {
        JobDefinitionDao defs = jobDefs.getIfAvailable();
        JobRunDao runs = jobRuns.getIfAvailable();
        if (defs == null || runs == null) {
            return null;
        }
        Map<String, JobRunRow> byName = runs.findAll().stream()
                .collect(Collectors.toMap(JobRunRow::jobName, r -> r, (a, b) -> a));
        return defs.findAll().stream()
                .map(d -> OpsJobController.JobVO.of(d, byName.get(d.jobName())))
                .toList();
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        clients.forEach(c -> c.emitter.complete());
        clients.clear();
    }
}
