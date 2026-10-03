package ai.neargo.shop.idem;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 幂等执行器：{@code Idempotency-Key} 命中就返回首次结果，否则执行并记账。
 *
 * <p>用法（下单/支付/退款/核销必接）：
 * <pre>{@code
 * return idempotency.execute(key, "POST /mp/order", userNo, OrderVO.class, () -> doCreate(cmd));
 * }</pre>
 *
 * <p><b>并发靠唯一索引</b>：两个请求同时进来，先插成功的执行业务，
 * 后插的撞 {@link DuplicateKeyException} → 回查首次结果。
 * 若首次还没执行完，后者拿到的是空结果 —— 此时宁可让它重试，也不能放它进去执行第二遍。
 */
@Service
public class IdempotencyService {

    /** 24h：足够覆盖用户手滑重提交与端侧重试，又不会让表无限膨胀。 */
    private static final int TTL_HOURS = 24;

    private final SysIdempotentMapper mapper;
    private final ObjectMapper json;

    public IdempotencyService(SysIdempotentMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public <T> T execute(String idemKey, String endpoint, String userNo, Class<T> type, Supplier<T> action) {
        if (idemKey == null || idemKey.isBlank()) {
            return action.get();   // 未带 key 的调用不做幂等，由调用方（前端）负责
        }
        SysIdempotent row = new SysIdempotent();
        row.setIdemKey(idemKey);
        row.setEndpoint(endpoint);
        row.setUserNo(userNo);
        row.setCreatedAt(LocalDateTime.now());
        row.setExpireAt(LocalDateTime.now().plusHours(TTL_HOURS));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            return replay(idemKey, endpoint, type);
        }

        T result = action.get();
        row.setResultJson(toJson(result));
        mapper.updateById(row);
        return result;
    }

    private <T> T replay(String idemKey, String endpoint, Class<T> type) {
        SysIdempotent existing = mapper.selectOne(Wrappers.<SysIdempotent>lambdaQuery()
                .eq(SysIdempotent::getIdemKey, idemKey)
                .eq(SysIdempotent::getEndpoint, endpoint));
        if (existing == null || existing.getResultJson() == null) {
            // 首次请求仍在执行中：告诉调用方稍后重试，绝不放行第二次执行
            throw new ConcurrentRequestException();
        }
        try {
            return json.readValue(existing.getResultJson(), type);
        } catch (Exception e) {
            throw new IllegalStateException("idempotent result deserialize failed", e);
        }
    }

    private String toJson(Object result) {
        try {
            return result == null ? "null" : json.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("idempotent result serialize failed", e);
        }
    }

    /** 同 key 的首次请求尚未完成。 */
    public static class ConcurrentRequestException extends RuntimeException {
        public ConcurrentRequestException() {
            super("duplicated request in flight");
        }
    }
}
