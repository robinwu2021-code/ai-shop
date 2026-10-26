package ai.neargo.shop.pay.openapp.service;

import ai.neargo.shop.pay.access.PayCaller;
import java.util.List;
import java.util.Set;

/**
 * 商家授权第三方代本店操作（F44）：门店 × 接入方一行，动作 COLLECT / REFUND / READ。只能授权给<b>启用中的第三方接入方</b>
 * （内部接入方不经商家授权）；撤销即时生效（开放 API 每次请求都按授权表换出调用方）。
 */
public interface AppGrantService {

    List<GrantView> ofStore(PayCaller caller, Long storeId);

    /** 同一接入方再授权一次 = 改动作、恢复为 ACTIVE（一店一行） */
    GrantView grant(PayCaller caller, Long storeId, String appNo, Set<String> actions);

    void revoke(PayCaller caller, Long grantId);

    record GrantView(Long id, String appNo, String appName, List<String> actions, String status, Long grantedAt) {
    }
}
