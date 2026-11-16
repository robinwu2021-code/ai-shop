package ai.neargo.shop.common;

import java.util.Map;

/**
 * A business error whose envelope {@code data} carries machine-readable details (for example the
 * rejected field key and reason). Details must never contain submitted values.
 */
public class BizDetailedException extends BizException {
    private final transient Map<String, Object> details;

    public BizDetailedException(ErrorCode code, Map<String, Object> details) {
        super(code);
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() {
        return details;
    }
}
