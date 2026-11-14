package ai.neargo.inventory.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.net.URI;

/**
 * 库存服务 · 商家后台接口（{@code /api/inventory/v1/console/**}）的通用传输口（M8 T8.3）。
 *
 * <p>后台端点多（几十个）、返回结构就是领域 VO：逐个写成 {@code @HttpExchange} 方法没有收益，
 * 用动态 URI 一个口转发，类型由 {@link InventoryReservationClient#console} 的调用方给。应答按原文取回再解。
 */
public interface InventoryConsoleApi {

    @GetExchange
    String get(URI uri, @RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
               @RequestHeader(InventoryReservationApi.ACTOR) String actor);

    @PostExchange
    String post(URI uri, @RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
                @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
                @RequestHeader(InventoryReservationApi.ACTOR) String actor, @RequestBody(required = false) Object body);

    @PutExchange
    String put(URI uri, @RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
               @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
               @RequestHeader(InventoryReservationApi.ACTOR) String actor, @RequestBody(required = false) Object body);

    @DeleteExchange
    String delete(URI uri, @RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
                  @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
                  @RequestHeader(InventoryReservationApi.ACTOR) String actor);
}
