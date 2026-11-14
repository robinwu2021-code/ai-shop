package ai.neargo.inventory.domain.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写命令幂等（TDD §2.12，AC-03）。
 *
 * <p>防住的是「请求成功但回包丢了，重试又发了一次货」：同一个幂等键第二次来，
 * 参数相同就返回第一次的结果、不再执行；参数不同是调用方的缺陷，直接 409。
 * 幂等记录与业务写入<b>同一个事务</b> —— 业务回滚，记录跟着没了，重试会重新执行。
 */
public interface CommandService {

    /**
     * 执行一条命令；同键同参返回原结果。
     *
     * @throws ai.neargo.inventory.api.InventoryException {@code BAD_REQUEST} 没有幂等键；
     *         {@code IDEMPOTENCY_CONFLICT} 同键异参
     */
    <T> T execute(Caller caller, String commandType, Object request, Class<T> resultType, Supplier<T> action);

    /** 按幂等键查结果：调用方不确定上一次成没成功时用它，而不是盲目重发。 */
    Optional<Stored> find(Caller caller, String requestId);

    record Stored(String commandType, String resultBody, LocalDateTime createdAt) {
    }
}
