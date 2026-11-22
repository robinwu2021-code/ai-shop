package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.PayBizSourceDelegation;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端统一认证接口（TDD-支付三端经网关透传与权限归属 §3.2）：支付服务拿商户端令牌来换「这个人在哪些支付商户下、各有哪些码」，
 * 权限由支付服务自己判。与运营端的 {@link OperatorIdentityEndpoint} 并列，取代 B 端转发时的「店主来源委托」。
 *
 * <p>结论与 B 端自己的判权同源：门店与角色按 {@link BizIdentityResolver}（{@code /biz/**} 用的那一个）解析，码取
 * {@link BizContext#effectivePerms()}；主体关联的支付商户与店主来源委托取同一处
 * （{@link PayBizSourceDelegation#payMerchantOf}）。
 * <ul>
 *   <li>店主：名下主体关联的支付商户，{@code role=OWNER}、{@code perms=["*"]}、{@code storeScope=ALL}；</li>
 *   <li>店员：按门店给码，{@code stores=[{storeNo, perms}]}，{@code perms} 是各店的并集；按 {@code X-Auth-Namespace}
 *       （支付传 {@code mch:pay:,biz:finance,biz:store:admin}，§13.5）筛完一个码都没有的主体不出现；</li>
 *   <li>主体没关联支付商户的不出现；带了 {@code X-Pay-Merchant-No} 只回那一个商户。</li>
 * </ul>
 *
 * <p>凭据自己比（{@code /internal/**} 不进安全链）：{@code shop.gateway.biz.identity.caller-token}，与运营端那把分开，
 * 常数时间比对，<b>没配 = 一律 403</b>。
 */
@RestController
@RequestMapping("/internal/auth/v1")
public class MerchantStaffIdentityEndpoint {

    private final String callerToken;
    private final TokenStore tokenStore;
    private final ObjectProvider<MerchantEntityService> entities;
    private final ObjectProvider<BizIdentityResolver> resolver;
    private final ObjectProvider<PaySourceLinkApi> links;
    private final ObjectProvider<StoreSyncProperties> sync;

    public MerchantStaffIdentityEndpoint(@Value("${shop.gateway.biz.identity.caller-token:}") String callerToken,
                                         TokenStore tokenStore, ObjectProvider<MerchantEntityService> entities,
                                         ObjectProvider<BizIdentityResolver> resolver,
                                         ObjectProvider<PaySourceLinkApi> links,
                                         ObjectProvider<StoreSyncProperties> sync) {
        this.callerToken = callerToken;
        this.tokenStore = tokenStore;
        this.entities = entities;
        this.resolver = resolver;
        this.links = links;
        this.sync = sync;
    }

    @GetMapping("/merchant-staff")
    public ResponseEntity<Map<String, Object>> merchantStaff(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Namespace", required = false) String namespace,
            @RequestHeader(value = "X-Pay-Merchant-No", required = false) String payMerchantNo) {
        if (!callerAllowed(given)) {
            return fail(HttpStatus.FORBIDDEN, 40300, "CALLER_NOT_ALLOWED");
        }
        MerchantEntityService entityService = entities.getIfAvailable();
        BizIdentityResolver identity = resolver.getIfAvailable();
        PaySourceLinkApi api = links.getIfAvailable();
        StoreSyncProperties props = sync.getIfAvailable();
        if (entityService == null || identity == null || api == null || props == null) {
            return fail(HttpStatus.SERVICE_UNAVAILABLE, 50300, "BIZ_END_NOT_ASSEMBLED");
        }
        Optional<LoginUser> session = bearer(authorization).flatMap(tokenStore::get).map(TokenStore.SessionData::user)
                .filter(u -> u.realm() == Realm.MERCHANT);
        if (session.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, 40100, "NO_MERCHANT_SESSION");
        }
        LoginUser user = session.get();
        List<Map<String, Object>> memberships = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EntityStoresVO group : entityService.myStores(user)) {
            Map<String, Object> row = membership(user, group, identity, namespace);
            if (row != null && seen.add(group.entity().entityNo())) {
                addIfLinked(memberships, row, api, props, payMerchantNo);
            }
        }
        // 名下还没建门店的主体也要能进件：店主身份不靠门店
        for (EntityVO owned : entityService.myEntities(user.userNo())) {
            if (seen.add(owned.entityNo())) {
                addIfLinked(memberships, owner(owned.entityNo()), api, props, payMerchantNo);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userNo", user.userNo());
        data.put("subjectKind", user.subjectKind() == null ? null : user.subjectKind().name());
        data.put("memberships", memberships);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "OK");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** 一张证照下本人的身份：店主整主体 {@code *}；店员逐店取码，一个码都没有返回 null */
    private static Map<String, Object> membership(LoginUser user, EntityStoresVO group, BizIdentityResolver identity,
                                                  String namespace) {
        String entityNo = group.entity().entityNo();
        List<StoreVO> stores = group.stores() == null ? List.of() : group.stores();
        if (stores.isEmpty()) {
            return null;
        }
        // 按店反查主体：解析出来的主体必须就是这张证照（解析器对不属于本人的门店会回落到默认主体）
        BizContext ctx = identity.resolveAuthenticated(user, stores.getFirst().storeNo());
        if (ctx == null || !entityNo.equals(ctx.merchantNo())) {
            return null;
        }
        if (ctx.owner()) {
            return owner(entityNo);
        }
        List<Map<String, Object>> perStore = new ArrayList<>();
        Set<String> union = new TreeSet<>();
        for (StoreVO store : stores) {
            if (!ctx.storeNos().contains(store.storeNo())) {
                continue;
            }
            List<String> perms = OperatorIdentityEndpoint.filter(
                    List.copyOf(new TreeSet<>(ctx.withStore(store.storeNo()).effectivePerms())), namespace);
            if (!perms.isEmpty()) {
                perStore.add(Map.of("storeNo", store.storeNo(), "perms", perms));
                union.addAll(perms);
            }
        }
        if (union.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityNo", entityNo);
        row.put("role", "STAFF");
        row.put("perms", List.copyOf(union));
        row.put("storeScope", "STORES");
        row.put("stores", perStore);
        return row;
    }

    private static Map<String, Object> owner(String entityNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityNo", entityNo);
        row.put("role", "OWNER");
        row.put("perms", List.of("*"));
        row.put("storeScope", "ALL");
        row.put("stores", List.of());
        return row;
    }

    /** 主体关联到支付商户才回；带了指定商户号只回那一个 */
    private static void addIfLinked(List<Map<String, Object>> out, Map<String, Object> row, PaySourceLinkApi api,
                                    StoreSyncProperties props, String requested) {
        String merchant = PayBizSourceDelegation.payMerchantOf(api, props.sourceScope(), (String) row.get("entityNo"));
        if (merchant == null || (requested != null && !requested.isBlank() && !requested.trim().equals(merchant))) {
            return;
        }
        Map<String, Object> withMerchant = new LinkedHashMap<>();
        withMerchant.put("payMerchantNo", merchant);
        withMerchant.putAll(row);
        out.add(withMerchant);
    }

    private boolean callerAllowed(String given) {
        if (callerToken == null || callerToken.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(callerToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, int code, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", reasonCode);
        body.put("reasonCode", reasonCode);
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }
}
