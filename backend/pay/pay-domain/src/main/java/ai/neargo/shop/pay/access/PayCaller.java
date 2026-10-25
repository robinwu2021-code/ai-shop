package ai.neargo.shop.pay.access;

import ai.neargo.shop.pay.channel.api.CallerType;
import java.util.Objects;
import java.util.Set;

/**
 * 一次调用的身份（代码清单 §2.1）。支付域的每个入口第一件事是拿它过 {@link ai.neargo.shop.pay.access.policy.CallerPolicy}。
 *
 * <p><b>只能由合法的鉴权组件构造</b>：内部调用方经 {@code InternalCallers}；开放 API、终端、收银台在 I5 / I7 各自的鉴权组件。
 * 守卫是架构测试 {@code PayCallerFactoryTest} —— record 的构造器藏不住，只能靠扫描。
 *
 * @param callerId     PARTNER / INTERNAL → {@code pay_app.id}；TERMINAL → {@code pay_terminal.id}；BIZ / OPS → 账号 id
 * @param storeScope   允许操作的门店 {@code mch_store.id}；内部系统也受它约束 —— 防的是代码 bug，不是恶意
 * @param operatorNo   商家操作员 / 收银员。<b>退款必填</b>
 * @param authorizerNo 店长授权人（终端退款、撤销）
 */
public record PayCaller(CallerType type, Long callerId, Set<Long> storeScope, String operatorNo, String authorizerNo,
                        Long userId) {

    public PayCaller {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callerId, "callerId");
        storeScope = storeScope == null ? Set.of() : Set.copyOf(storeScope);
    }

    public boolean covers(Long storeId) {
        return storeId != null && storeScope.contains(storeId);
    }

    public boolean hasOperator() {
        return operatorNo != null && !operatorNo.isBlank();
    }
}
