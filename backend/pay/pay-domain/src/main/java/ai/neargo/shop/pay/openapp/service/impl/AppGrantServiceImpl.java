package ai.neargo.shop.pay.openapp.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppGrantMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.entity.PayAppGrant;
import ai.neargo.shop.pay.openapp.service.AppGrantService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link AppGrantService} 的实现（TDD-支付服务-I5接入层 S8） */
@Service
public class AppGrantServiceImpl implements AppGrantService {

    private static final Set<String> ACTIONS = Set.of("COLLECT", "REFUND", "READ");

    private final PayAppGrantMapper grants;
    private final PayAppMapper apps;
    private final CallerPolicy policy;

    public AppGrantServiceImpl(PayAppGrantMapper grants, PayAppMapper apps, CallerPolicy policy) {
        this.grants = grants;
        this.apps = apps;
        this.policy = policy;
    }

    @Override
    public List<GrantView> ofStore(PayCaller caller, Long storeId) {
        policy.check(caller, PayAction.QUERY, storeId);
        List<PayAppGrant> rows = DataScopeContext.executeWithoutScope(() -> grants.selectList(
                Wrappers.<PayAppGrant>lambdaQuery().eq(PayAppGrant::getStoreId, storeId).orderByDesc(PayAppGrant::getId)));
        Map<Long, PayApp> byId = rows.isEmpty() ? Map.of() : DataScopeContext.executeWithoutScope(() -> apps.selectBatchIds(
                rows.stream().map(PayAppGrant::getPayAppId).toList())).stream()
                .collect(Collectors.toMap(PayApp::getId, Function.identity()));
        return rows.stream().map(g -> view(g, byId.get(g.getPayAppId()))).toList();
    }

    @Override
    public GrantView grant(PayCaller caller, Long storeId, String appNo, Set<String> actions) {
        policy.check(caller, PayAction.QUERY, storeId);
        if (actions == null || actions.isEmpty() || !ACTIONS.containsAll(actions)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PayApp app = appNo == null ? null : DataScopeContext.executeWithoutScope(() -> apps.selectOne(
                Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo)));
        if (app == null || !"PARTNER".equals(app.getOwnerType()) || !"ACTIVE".equals(app.getStatus())) {
            // 内部接入方、停用的、不存在的同一个回答：商家不该能探测接入方名单
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String joined = String.join(",", new TreeSet<>(actions));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PayAppGrant existing = DataScopeContext.executeWithoutScope(() -> grants.selectOne(Wrappers.<PayAppGrant>lambdaQuery()
                .eq(PayAppGrant::getPayAppId, app.getId()).eq(PayAppGrant::getStoreId, storeId)));
        PayAppGrant g = existing == null ? new PayAppGrant() : existing;
        g.setPayAppId(app.getId());
        g.setStoreId(storeId);
        g.setActions(joined);
        g.setStatus("ACTIVE");
        g.setGrantedBy(caller.operatorNo() == null ? "UNKNOWN" : caller.operatorNo());
        g.setGrantedAt(now);
        g.setRevokedAt(null);
        if (existing == null) {
            DataScopeContext.executeWithoutScope(() -> grants.insert(g));
        } else {
            DataScopeContext.executeWithoutScope(() -> grants.update(null, Wrappers.<PayAppGrant>lambdaUpdate()
                    .eq(PayAppGrant::getId, g.getId()).set(PayAppGrant::getActions, joined)
                    .set(PayAppGrant::getStatus, "ACTIVE").set(PayAppGrant::getGrantedBy, g.getGrantedBy())
                    .set(PayAppGrant::getGrantedAt, now).set(PayAppGrant::getRevokedAt, null)));
        }
        return view(g, app);
    }

    @Override
    public void revoke(PayCaller caller, Long grantId) {
        PayAppGrant g = grantId == null ? null : DataScopeContext.executeWithoutScope(() -> grants.selectById(grantId));
        if (g == null || !caller.covers(g.getStoreId())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        DataScopeContext.executeWithoutScope(() -> grants.update(null, Wrappers.<PayAppGrant>lambdaUpdate()
                .eq(PayAppGrant::getId, g.getId()).set(PayAppGrant::getStatus, "REVOKED")
                .set(PayAppGrant::getRevokedAt, LocalDateTime.now(ZoneOffset.UTC))));
    }

    private static GrantView view(PayAppGrant g, PayApp app) {
        return new GrantView(g.getId(), app == null ? null : app.getAppNo(), app == null ? null : app.getName(),
                Arrays.asList(g.getActions().split(",")), g.getStatus(),
                g.getGrantedAt() == null ? null : g.getGrantedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
