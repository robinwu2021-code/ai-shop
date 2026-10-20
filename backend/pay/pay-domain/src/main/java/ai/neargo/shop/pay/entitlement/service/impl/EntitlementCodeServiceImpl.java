package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntCodeTokenMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Issuing is holder-only; resolving proves only one asset and never reads a wallet or all of its holder's rights. */
@Service
public class EntitlementCodeServiceImpl implements EntitlementCodeService {
    private final EntCodeTokenMapper tokens;
    private final EntitlementCodeWriter writer;
    private final ObjectMapper json;
    public EntitlementCodeServiceImpl(EntCodeTokenMapper tokens, EntitlementCodeWriter writer, ObjectMapper json) {
        this.tokens = tokens; this.writer = writer; this.json = json;
    }
    @Override public CodeView issue(EntitlementContext ctx, String user, String instance,
                                    CodeRequest request, String key) {
        holder(ctx, user); ContractChecks.id(instance); ContractChecks.id(key); ContractChecks.present(request);
        String payload = digest(json.writeValueAsString(List.of(user, instance, request.useContext())));
        var prior = DataScopeContext.executeWithoutScope(() ->
                tokens.findRequest(ctx.tenantNo(), user, digest(key)));
        if (prior != null) {
            if (!prior.getPayloadHash().equals(payload)) conflict();
            return writer.view(prior);
        }
        return writer.issue(ctx, user, instance, digest(key), payload);
    }
    @Override public ResolvedCode resolve(EntitlementContext ctx, String entity, String store,
                                         String token, String op) {
        ContractChecks.id(entity); ContractChecks.id(store); ContractChecks.id(op);
        if (token == null || !token.matches("ec1_[A-Za-z0-9_-]{43}")) throw BizException.of(ErrorCode.NOT_FOUND);
        return writer.resolve(ctx, entity, store, digest(token), op);
    }
    @Override public int invalidate(EntitlementContext ctx, String user, String instance) {
        holder(ctx, user); ContractChecks.id(instance); return writer.invalidate(ctx, user, instance);
    }
    private static void holder(EntitlementContext ctx, String user) {
        ContractChecks.id(user);
        if (!user.equals(ctx.actorNo())) throw BizException.of(ErrorCode.FORBIDDEN);
    }
}
