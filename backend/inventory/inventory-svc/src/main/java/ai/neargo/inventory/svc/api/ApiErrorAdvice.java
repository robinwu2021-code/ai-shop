package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.guard.ErrorBodies;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 领域异常 → {@code {code, message, data}} + 对应 HTTP 状态（TDD §2.13）。与过滤器写出的错误体同一个形状。 */
@RestControllerAdvice
public class ApiErrorAdvice {

    private final MeterRegistry registry;

    public ApiErrorAdvice(MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<String> onInventory(InventoryException e) {
        // 按错误码计数：冲突（409）多了是并发或宿主重试有问题，400 多了是宿主的请求有问题
        registry.counter("inventory.errors", "code", e.error().key(),
                "status", String.valueOf(e.error().httpStatus())).increment();
        return ResponseEntity.status(e.error().httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBodies.body(e));
    }
}
