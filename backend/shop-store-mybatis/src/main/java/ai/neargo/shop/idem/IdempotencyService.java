package ai.neargo.shop.idem;

import ai.neargo.shop.idem.port.RequestIdempotencyPortImpl;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 过渡用：还没迁到统一防重端口 {@code RequestIdempotencyPort} 的调用方经它转给唯一实现
 * {@link RequestIdempotencyPortImpl}（TDD-基础能力库对齐 §2.2 1g）。最后一批迁完即删。
 */
@Service
public class IdempotencyService {

    private final RequestIdempotencyPortImpl port;

    public IdempotencyService(SysIdempotentMapper mapper, ObjectMapper json) {
        this.port = new RequestIdempotencyPortImpl(mapper, json);
    }

    public <T> T execute(String idemKey, String endpoint, String userNo, Class<T> type, Supplier<T> action) {
        return port.execute(idemKey, endpoint, userNo, type, action);
    }

    public <T> T execute(String idemKey, String endpoint, String userNo, String requestHash,
                         Class<T> type, Supplier<T> action) {
        return port.execute(idemKey, endpoint, userNo, requestHash, type, action);
    }

    public <T> Optional<T> findCompleted(String idemKey, String endpoint, String userNo, String requestHash,
            Class<T> type) {
        return port.findCompleted(idemKey, endpoint, userNo, requestHash, type);
    }
}
