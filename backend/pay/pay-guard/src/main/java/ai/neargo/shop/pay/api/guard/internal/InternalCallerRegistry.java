package ai.neargo.shop.pay.api.guard.internal;

import java.util.Optional;

/** 内部调用方识别（代码清单 §5.3）：调用方码 + 凭据 → 认得就返回这个调用方码 */
public interface InternalCallerRegistry {

    Optional<String> resolve(String callerCode, String credential);
}
