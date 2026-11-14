package ai.neargo.shop.merchant.service.impl;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.StoreCreationAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class StoreCreationAccessServiceImpl implements StoreCreationAccessService {
    private final MerchantEntityService entities;
    private final MerchantPlanService plans;
    private final boolean enabled;
    public StoreCreationAccessServiceImpl(MerchantEntityService entities,MerchantPlanService plans,@Value("${commerce.store-creation.enabled:false}") boolean enabled) {
        this.entities=entities;this.plans=plans;this.enabled=enabled;
    }
    public Access access(LoginUser user) {
        if(!enabled || user.realm()!=Realm.MERCHANT || !Set.of(SubjectKind.USR,SubjectKind.MCH).contains(user.subjectKind())) return new Access(false,false,List.of());
        var owned=entities.myStores(user).stream().filter(g->g.entity().canManage()).toList();
        var sources=new ArrayList<Source>();
        for(var group:owned) {
            if(!Set.of("ACTIVE","PENDING_LICENSE").contains(group.entity().status()))continue;
            var anchor=group.stores().stream().filter(s->"ACTIVE".equals(s.status()) && s.market()!=null && !s.market().isBlank()).findFirst();
            if(anchor.isEmpty())continue;
            var plan=plans.mine(group.entity().entityNo());var store=anchor.get();
            sources.add(new Source(group.entity().entityNo(),group.entity().name(),store.storeNo(),store.market(),plan.storeQuota(),plan.storeUsed(),plan.storeUsed()<plan.storeQuota()));
        }
        return new Access(user.subjectKind()==SubjectKind.USR && owned.size()<MerchantAdminPort.MAX_ENTITIES_PER_ACCOUNT && owned.stream().noneMatch(g->"PENDING_LICENSE".equals(g.entity().status())),user.subjectKind()==SubjectKind.USR && owned.stream().anyMatch(g->"PENDING_LICENSE".equals(g.entity().status())),List.copyOf(sources));
    }
}
