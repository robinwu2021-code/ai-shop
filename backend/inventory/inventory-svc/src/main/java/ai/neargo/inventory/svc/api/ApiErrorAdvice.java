package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.guard.ErrorBodies;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 领域异常 → {@code {code, message, data}} + 对应 HTTP 状态（TDD §2.13）。与过滤器写出的错误体同一个形状。 */
@RestControllerAdvice
public class ApiErrorAdvice {

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<String> onInventory(InventoryException e) {
        return ResponseEntity.status(e.error().httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBodies.body(e));
    }
}
