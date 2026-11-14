package ai.neargo.shop.member.scope;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import ai.neargo.shop.member.service.*;import ai.neargo.shop.member.service.impl.MemberCodeResolveServiceImpl;import ai.neargo.shop.member.dto.*;import ai.neargo.shop.member.entity.MbrIdentityTicket;import ai.neargo.shop.member.mapper.MemberIdentityTicketMapper;import ai.neargo.shop.member.config.MemberCodeProperties;import ai.neargo.shop.spi.user.*;import ai.neargo.shop.spi.member.MemberOperatingScopePort;import ai.neargo.shop.common.BizException;import java.util.*;import java.util.concurrent.atomic.AtomicReference;
class MemberScopeReferenceTest {
 @Test void scopeReferenceExpiresWhenOrganizationRevisionChangesAndNeverWorksInAnotherStore() {
  var tickets=mock(MemberIdentityTicketMapper.class);var users=mock(UserScopeFactsPort.class);var merchants=mock(MerchantScopeFactsPort.class);var properties=new MemberCodeProperties();properties.setEnabled(true);var scopeMembers=mock(MemberScopeService.class);var people=mock(PersonPort.class);
  when(merchants.entityActive("MAIN","E1")).thenReturn(true);when(merchants.storesOwned(eq("MAIN"),eq("E1"),anySet())).thenReturn(true);
  var m=new MemberScopeModels.ScopeMember("MSM1","P1","BRAND","B1","S1","LEAD");when(scopeMembers.require("MAIN","E1","S1","MSM1")).thenReturn(m);when(scopeMembers.identity("MAIN","P1")).thenReturn(new MemberIdentityFactsPort.Identity("P1",null,"138****8000",true));
  when(scopeMembers.scope("MAIN","E1","S1")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E1","S1","BRAND","B1",1,"甲店"));when(people.find("P1")).thenReturn(Optional.of(new PersonPort.PersonView("P1","8000",null,"138****8000")));
  var row=new AtomicReference<MbrIdentityTicket>();when(tickets.insert(any(MbrIdentityTicket.class))).thenAnswer(a->{row.set(a.getArgument(0));return 1;});when(tickets.selectOne(any())).thenAnswer(a->row.get());
  var service=new MemberCodeResolveServiceImpl(tickets,users,merchants,properties);service.setPeople(people);service.setScopeMembers(scopeMembers);
  var context=new MemberCodeScope("MAIN","E1","S1","A");var ref=service.selectScopedCustomer(context,"MSM1");assertThat(service.requireReference(context,ref.customerRef()).customerNo()).isEqualTo("P1");
  assertThatThrownBy(()->service.requireReference(new MemberCodeScope("MAIN","E1","S2","A"),ref.customerRef())).isInstanceOf(BizException.class);
  when(scopeMembers.scope("MAIN","E1","S1")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E1","S1","BRAND","B1",2,"甲店"));assertThatThrownBy(()->service.requireReference(context,ref.customerRef())).isInstanceOf(BizException.class);
 }
}
