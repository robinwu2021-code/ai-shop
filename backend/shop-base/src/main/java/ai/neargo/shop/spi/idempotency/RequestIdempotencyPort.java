package ai.neargo.shop.spi.idempotency;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 接口级防重（请求防重）—— 基础能力的统一端口（TDD-基础能力库对齐 §2.2 1g，Q11 选 A）。
 *
 * <p>调用方只依赖它，不依赖实现与回执表；回执表随基础能力换库时调用方一行不改。
 * 与事件级幂等（{@code EventIdempotency}，键 = 事件号 + 处理者、不存结果）是两回事，刻意不合并。
 *
 * <p>实现必须满足的语义（逐条有测试）：
 * <ol>
 *   <li>同键 + 同端点 + 同人只执行一次，之后返回<b>首次结果原样</b>；</li>
 *   <li>同键、请求摘要不同 → {@code CONFLICT}；</li>
 *   <li>同键、换了人 → {@code NOT_FOUND}，不泄露别人的结果；</li>
 *   <li>首次还没完成时的并发重复 → {@link ConcurrentRequestException}，绝不放行第二次；</li>
 *   <li>不带键 → 不防重，直接执行；</li>
 *   <li>回执跟着调用方的事务：业务回滚，回执一起回滚；</li>
 *   <li>{@link #findCompleted} 只读已完成的回执，不占位、不加锁；</li>
 *   <li>回执不清理：有调用方用确定键（如定价种子版本）依赖它永久有效。</li>
 * </ol>
 */
public interface RequestIdempotencyPort {

    /** 不校验请求内容的旧调用方式。 */
    <T> T execute(String idemKey, String endpoint, String userNo, Class<T> type, Supplier<T> action);

    /** @param requestHash 请求内容摘要；同键内容变了即 {@code CONFLICT}，为空则不校验 */
    <T> T execute(String idemKey, String endpoint, String userNo, String requestHash,
                  Class<T> type, Supplier<T> action);

    /** 只读已完成的回执；没有（含首次仍在执行）返回空，调用方随后仍要走 {@link #execute}。 */
    <T> Optional<T> findCompleted(String idemKey, String endpoint, String userNo, String requestHash, Class<T> type);

    /** 同键的首次请求尚未完成。 */
    class ConcurrentRequestException extends RuntimeException {
        public ConcurrentRequestException() {
            super("duplicated request in flight");
        }
    }
}
