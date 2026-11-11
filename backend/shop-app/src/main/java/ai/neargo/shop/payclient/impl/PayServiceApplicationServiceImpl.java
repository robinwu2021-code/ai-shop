package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.merchant.paysync.PayServiceApplicationStore;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PayServiceApplicationService;
import ai.neargo.shop.payclient.PayServiceApplyRequests;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Same request key replays the exact frozen actor, entity and store snapshots after an uncertain response. */
@Service
public final class PayServiceApplicationServiceImpl implements PayServiceApplicationService {
    private final PayServiceApplicationStore submissions;
    private final StoreSnapshotReader stores;
    private final StoreSyncProperties props;
    private final ObjectProvider<PaySourceLinkApi> links;
    private final ObjectMapper json;
    public PayServiceApplicationServiceImpl(PayServiceApplicationStore submissions,StoreSnapshotReader stores,
            StoreSyncProperties props,ObjectProvider<PaySourceLinkApi> links,ObjectMapper json) {
        this.submissions=submissions;this.stores=stores;this.props=props;this.links=links;this.json=json;
    }
    @Override public PaySourceLinkApi.Result apply(String entityNo,PayServiceApplyRequests.Form form,String realm,String actorNo) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("pay service submission requires no ambient transaction");
        var api=links.getIfAvailable();
        if(api==null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        var entity=stores.entity(entityNo).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
        var normalized=PayServiceApplyRequests.build(entity,List.of(),form,realm,actorNo,props).profile();
        String hash=IdempotencyScope.requestHash("PAY_SERVICE_APPLICATION_V1",entityNo,realm,actorNo,json.writeValueAsString(normalized));
        var original=submissions.freeze(entityNo,props.caller(),props.sourceScope(),form.requestNo(),realm,actorNo,hash,revision->{
            var current=stores.entity(entityNo).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
            var body=PayServiceApplyRequests.build(current,stores.storesOfEntity(entityNo),form,realm,actorNo,props);
            return json.writeValueAsString(new PaySourceLinkApi.Apply("ais-pay-"+entityNo+"-"+revision,
                    body.sourceScope(),body.sourceEntityRef(),revision,body.entityName(),body.stores(),body.actorRef(),
                    body.capabilityType(),revision,body.profile()));
        });
        var request=json.readValue(original.requestJson(),PaySourceLinkApi.Apply.class);
        if(!original.sourceEventId().equals(request.sourceEventId()) || original.profileRevision()!=request.profileRevision()
                || original.profileRevision()!=request.entityRevision())
            throw BizException.of(ErrorCode.CONFLICT);
        try { return api.apply(request); }
        catch(ServiceCallException failure) {
            throw BizException.of(failure.statusCode()==400?ErrorCode.BAD_REQUEST:
                    failure.statusCode()==409?ErrorCode.CONFLICT:ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
    }
}
