package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.brand.IssuerAuthorityService;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.user.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueMigrationCoordinatorTest {
    final Actor actor=new Actor("MAIN","MCH:OWNER");
    final StoreAuthority scope=new StoreAuthority("MAIN","MCH:OWNER","E1","S1","本店",7);
    final IssuerAuthorityService authority=mock(IssuerAuthorityService.class);
    final StoredValueMigrationAppService pay=mock(StoredValueMigrationAppService.class);
    final MemberIdentityFactsPort identities=mock(MemberIdentityFactsPort.class);
    final PersonPort people=mock(PersonPort.class);
    StoredValueMigrationCoordinator coordinator(){return new StoredValueMigrationCoordinator(authority,pay,identities,people);}
    void probe(Subject... subjects){
        when(authority.storePreparationScope(actor,"S1")).thenReturn(scope);
        when(pay.probe(List.of(scope))).thenReturn(new Probe(List.of(new Scope(scope,"I1")),List.of(subjects)));
        when(pay.prepare(any(),eq("K1"))).thenAnswer(i->{Preparation p=i.getArgument(0);return new Manifest("M1","fingerprint","authority",p.identities().size());});
    }
    @Test void canonicalAndLegacyUserReferencesResolveWithoutPhoneOrBindingWrites(){
        probe(new Subject("A1","S1","E1","U1","U1"),new Subject("A2","S1","E1","P2",null));
        when(people.findByUser("U1")).thenReturn(Optional.of(new PersonPort.PersonView("P1","0000","U1","****0000")));
        when(identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","U1","****0000",true)));
        when(identities.find("MAIN","P2")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P2",null,"****0000",true)));
        var service=coordinator();assertThat(service.prepare(actor,List.of("S1"),"K1").accountCount()).isEqualTo(2);
        var captor=org.mockito.ArgumentCaptor.forClass(Preparation.class);verify(pay).prepare(captor.capture(),eq("K1"));
        assertThat(captor.getValue().identities()).containsExactly(new Identity("P1","U1"),new Identity("P2",null));
        verify(people).findByUser("U1");verifyNoMoreInteractions(people);
    }
    @Test void foreignStoreAndInconsistentUserClaimsCannotReachPreparation(){
        probe(new Subject("A1","S2","E1","P1","U1"));
        assertThatThrownBy(()->coordinator().prepare(actor,List.of("S1"),"K1")).isInstanceOf(BizException.class);
        verifyNoInteractions(identities,people);verify(pay,never()).prepare(any(),any());
        probe(new Subject("A1","S1","E1","P1","U1"));
        when(identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1","OTHER",null,true)));
        assertThatThrownBy(()->coordinator().prepare(actor,List.of("S1"),"K1")).isInstanceOf(BizException.class);
        verify(pay,never()).prepare(any(),any());
    }
    @Test void liveStoreRevisionIsCheckedAgainBeforePersistingTheManifest(){
        probe(new Subject("A1","S1","E1","P1",null));
        when(identities.find("MAIN","P1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("P1",null,null,true)));
        when(authority.storePreparationScope(actor,"S1")).thenReturn(scope,new StoreAuthority("MAIN","MCH:OWNER","E1","S1","本店",8));
        assertThatThrownBy(()->coordinator().prepare(actor,List.of("S1"),"K1")).isInstanceOf(BizException.class);
        verify(pay,never()).prepare(any(),any());
    }
}
