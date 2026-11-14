package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.job.InvDailySnapshotJob;
import ai.neargo.inventory.domain.job.ReservationExpiryJob;
import ai.neargo.inventory.guard.InstanceProperties;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHttpPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 共用任务服务调库存服务的两个内部口（TDD §2.3「定时任务」，M8 T8.1）。契约与商城的
 * {@code JobHandlerEndpoint} 相同（{@link JobHttpPaths}），任务体在本进程里。
 *
 * <p><b>任务名带实例代号</b>（{@code inventory-shop-reservation-expiry}）：任务服务按名字登记，
 * 生产两套各是一个目标，商城内嵌时期的 {@code inv-reservation-expiry} 也还在 —— 同名就会互相顶掉。
 *
 * <p>与商城那边同样的四条：只绑回环（不经 nginx）；共享密钥不是用户身份；不记请求体；
 * 密钥没配一律 401（装不装跟着 {@code inventory.jobs.enabled}，不跟着密钥）。
 * 同一任务在本实例里并发触发时第二个回 409（任务服务据此不计失败）。
 */
@RestController
@ConditionalOnInventory
@ConditionalOnProperty(name = "inventory.jobs.enabled", havingValue = "true")
public class InventoryJobEndpoint {

    private static final Logger log = LoggerFactory.getLogger(InventoryJobEndpoint.class);

    private final Map<String, Handler> handlers = new LinkedHashMap<>();
    private final String token;

    public InventoryJobEndpoint(ReservationExpiryJob expiry, InvDailySnapshotJob snapshot, InstanceProperties instance,
                                @Value("${inventory.jobs.internal-token:}") String token) {
        String prefix = "inventory-" + instance.getCode().toLowerCase(Locale.ROOT) + "-";
        String module = "inventory-svc@" + instance.getCode();
        add(new JobDeclaration(prefix + "reservation-expiry", "库存预留到期回收",
                "回收到期未确认的预留，一轮最多 200 条；不跑则未付款订单的预留永久占货", module,
                "0 * * * * *", true, 120, 300, true, false), expiry::expireOnce);
        add(new JobDeclaration(prefix + "daily-snapshot", "库存日快照",
                "T+1 结算昨天的日快照", module, "0 30 3 * * *", true, 1800, 1800, true, true),
                snapshot::buildYesterday);
        this.token = token;
    }

    @GetMapping(JobHttpPaths.DECLARATIONS)
    public ResponseEntity<List<JobDeclaration>> declarations(
            @RequestHeader(value = JobHttpPaths.TOKEN_HEADER, required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(handlers.values().stream().map(Handler::declaration).toList());
    }

    @PostMapping(JobHttpPaths.RUN)
    public ResponseEntity<RunResp> run(@PathVariable String handlerName,
                                       @RequestHeader(value = JobHttpPaths.TOKEN_HEADER, required = false) String given) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Handler handler = handlers.get(handlerName);
        if (handler == null) {
            log.warn("任务服务要跑一个不存在的任务：{}", handlerName);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!handler.lock().tryLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new RunResp("SKIPPED", "running", null));
        }
        try {
            String detail = handler.body().get();
            return ResponseEntity.ok(new RunResp("SUCCESS", detail == null ? "-" : detail, null));
        } catch (RuntimeException e) {
            log.error("任务 {} 失败", handlerName, e);
            return ResponseEntity.ok(new RunResp("FAILED", null, e.getClass().getSimpleName()));
        } finally {
            handler.lock().unlock();
        }
    }

    private void add(JobDeclaration declaration, Supplier<String> body) {
        handlers.put(declaration.handlerName(), new Handler(declaration, body, new ReentrantLock()));
    }

    private boolean authorized(String given) {
        return !token.isBlank() && given != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private record Handler(JobDeclaration declaration, Supplier<String> body, ReentrantLock lock) {
    }

    public record RunResp(String status, String detail, String error) {
    }
}
