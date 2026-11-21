package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.svc.client.ServiceCallException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A current diagnostic; execution rechecks local facts and obtains a durable Pay reservation. */
@Component
public class StoreRelinkPrecheck {
    public record Inspection(boolean allowed,List<String> blockers,int revokedStaff,boolean executionReady,long storeVersion) {}
    private final StoreRelinkLocalPolicy local;
    private final ObjectProvider<PaySourceLinkApi> payments;
    private final StoreSyncProperties properties;
    private final StoreSnapshotReader snapshots;
    private final boolean enabled;
    public StoreRelinkPrecheck(StoreRelinkLocalPolicy local,ObjectProvider<PaySourceLinkApi> payments,
            StoreSyncProperties properties,StoreSnapshotReader snapshots,
            @Value("${shop.merchant.store-claim.enabled:false}") boolean enabled) {
        this.local=local;this.payments=payments;this.properties=properties;this.snapshots=snapshots;this.enabled=enabled;
    }
    public Inspection inspect(String tenant,String user,String source,String store,String target) {
        var checked=local.inspect(tenant,user,source,store,target,null);
        var blockers=new ArrayList<>(checked.blockers());
        var api=payments.getIfAvailable();
        boolean complete=false;
        if(api==null) blockers.add("PAY_SERVICE_UNKNOWN");
        else {
            var snapshot=snapshots.read(store).orElseThrow();
            try {
                var pay=api.relinkCheck(properties.sourceScope(),source,Long.toString(snapshot.storeId()),store);
                complete=pay.complete();
                if(!pay.complete()) blockers.add("FINANCIAL_HISTORY_UNKNOWN");
                if(pay.sourceApplied()) blockers.add("PAY_SERVICE_APPLIED");
                if(pay.associationEver()||pay.paymentHistory()||pay.storedValueHistory()) blockers.add("HAS_TRADES");
                blockers.addAll(pay.blockers());
            } catch(ServiceCallException unavailable) {blockers.add("PAY_SERVICE_UNKNOWN");}
        }
        if(!enabled) blockers.add("RELINK_CONCURRENCY_UNAVAILABLE");
        var unique=blockers.stream().distinct().toList();
        return new Inspection(enabled&&complete&&unique.isEmpty(),unique,checked.revokedStaff(),enabled&&complete,
                ((Number)checked.store().get("version")).longValue());
    }
}
