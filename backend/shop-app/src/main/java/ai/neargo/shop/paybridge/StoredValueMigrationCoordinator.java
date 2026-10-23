package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.IssuerAuthorityService;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import ai.neargo.shop.spi.user.PersonPort;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal audit orchestration: read live owners and canonical identities before entering a Pay transaction. */
@Service
public class StoredValueMigrationCoordinator {
    private final IssuerAuthorityService authority;
    private final StoredValueMigrationAppService pay;
    private final MemberIdentityFactsPort identities;
    private final PersonPort people;
    public StoredValueMigrationCoordinator(IssuerAuthorityService authority,StoredValueMigrationAppService pay,
            MemberIdentityFactsPort identities,PersonPort people) {
        this.authority=authority;this.pay=pay;this.identities=identities;this.people=people;
    }
    public Manifest prepare(Actor actor,List<String> stores,String key) {
        ContractChecks.id(key);return pay.prepare(evidence(actor,stores),key);
    }
    Preparation evidence(Actor actor,List<String> stores) {
        if(actor==null||!"MAIN".equals(actor.tenantNo())||stores==null||stores.isEmpty()||stores.size()>100
                ||stores.stream().distinct().count()!=stores.size())throw BizException.of(ErrorCode.BAD_REQUEST);
        var scopes=stores.stream().map(ContractChecks::id).sorted().map(s->authority.storePreparationScope(actor,s)).toList();
        var probe=pay.probe(scopes);
        var byStore=new HashMap<String,Scope>();
        for(var scope:probe.scopes()) {
            if(!scopes.contains(scope.authority())||byStore.put(scope.authority().storeNo(),scope)!=null)throw BizException.of(ErrorCode.CONFLICT);
            ContractChecks.id(scope.issuerNo());
        }
        if(byStore.size()!=scopes.size()||probe.subjects().size()>1000)throw BizException.of(ErrorCode.CONFLICT);
        // Validate the entire Pay response before resolving any platform identity.
        var accounts=new HashSet<String>();
        for(var subject:probe.subjects()) {
            ContractChecks.id(subject.accountNo());ContractChecks.optionalId(subject.customerNo());ContractChecks.optionalId(subject.userNo());
            var scope=byStore.get(subject.storeNo());
            if(scope==null||!scope.authority().entityNo().equals(subject.entityNo())||!accounts.add(subject.accountNo()))throw BizException.of(ErrorCode.CONFLICT);
        }
        var proof=new TreeMap<String,Identity>();
        for(var subject:probe.subjects()) {
            String customer=subject.customerNo(),user=subject.userNo();
            if(customer==null||customer.equals(user)) {
                if(user==null)throw BizException.of(ErrorCode.CONFLICT);
                customer=people.findByUser(user).filter(p->user.equals(p.userNo())).orElseThrow(()->BizException.of(ErrorCode.CONFLICT)).personNo();
            }
            String canonical=customer;
            var identity=identities.find(actor.tenantNo(),canonical).filter(p->p.consistent()&&canonical.equals(p.customerNo()))
                    .orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
            if(user!=null&&!user.equals(identity.userNo()))throw BizException.of(ErrorCode.CONFLICT);
            var resolved=new Identity(identity.customerNo(),identity.userNo());
            var previous=proof.putIfAbsent(resolved.customerNo(),resolved);
            if(previous!=null&&!previous.equals(resolved))throw BizException.of(ErrorCode.CONFLICT);
        }
        // No remote calls under Pay's issuer/account locks. Detect authority changes during identity discovery.
        for(StoreAuthority scope:scopes)
            if(!scope.equals(authority.storePreparationScope(actor,scope.storeNo())))throw BizException.of(ErrorCode.CONFLICT);
        return new Preparation(probe.scopes(),List.copyOf(proof.values()));
    }
    void assertCurrent(Actor actor,Preparation evidence) {
        for(var scope:evidence.scopes())if(!scope.authority().equals(authority.storePreparationScope(actor,scope.authority().storeNo())))throw BizException.of(ErrorCode.CONFLICT);
    }

}
