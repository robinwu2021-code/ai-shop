package ai.neargo.shop.scenario;
import ai.neargo.shop.spi.points.StatementNoticePort.Notice;
import ai.neargo.shop.spi.user.StoreFinanceRecipientsPort;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.port.StatementNoticePortImpl;
import ai.neargo.shop.merchant.port.StoreFinanceRecipientsPortImpl;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.auth.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PointsStatementNoticeTest {
 @Test void empty_audience_never_claims_notification_success(){
  var recipients=mock(StoreFinanceRecipientsPort.class);var messages=mock(MessageService.class);
  when(recipients.recipients("M","ST")).thenReturn(List.of());
  assertThat(new StatementNoticePortImpl(recipients,messages).deliver(new Notice("S",1,"T","M","ST"))).isFalse();verifyNoInteractions(messages);
 }
 @Test void staff_system_notification_keeps_original_revision_dedup_and_store_link(){
  var recipients=mock(StoreFinanceRecipientsPort.class);var messages=mock(MessageService.class);
  when(recipients.recipients("M","ST")).thenReturn(List.of("U1","U2"));
  var service=new StatementNoticePortImpl(recipients,messages);var n=new Notice("S",2,"T","M","ST");
  assertThat(service.deliver(n)).isTrue();assertThat(service.deliver(n)).isTrue();
  verify(messages,times(2)).pushTo(eq("STAFF"),eq("U1"),eq(MessageService.SYSTEM),anyString(),anyString(),eq("/pages/points/index?storeNo=ST&typeCode=T&statementNo=S"),eq("PTS-STMT:S:2:U1"));
  verify(messages,times(2)).pushTo(eq("STAFF"),eq("U2"),eq(MessageService.SYSTEM),anyString(),anyString(),anyString(),eq("PTS-STMT:S:2:U2"));
 }
 @Test void partial_delivery_failure_is_retryable_not_a_false_receipt(){
  var recipients=mock(StoreFinanceRecipientsPort.class);var messages=mock(MessageService.class);
  when(recipients.recipients("M","ST")).thenReturn(List.of("U1","U2"));
  doThrow(new IllegalStateException("INBOX_UNAVAILABLE")).when(messages).pushTo(eq("STAFF"),eq("U2"),anyString(),anyString(),anyString(),anyString(),anyString());
  assertThatThrownBy(()->new StatementNoticePortImpl(recipients,messages).deliver(new Notice("S",1,"T","M","ST"))).hasMessage("INBOX_UNAVAILABLE");
 }
 @Test void recipients_must_have_finance_in_the_exact_store(){
  var accounts=mock(MchAccountMapper.class);var identities=mock(BizIdentityResolver.class);
  var finance=new MchAccount();finance.setUserNo("FIN");var other=new MchAccount();other.setUserNo("OTHER");var denied=new MchAccount();denied.setUserNo("CS");
  when(accounts.selectList(any())).thenReturn(List.of(finance,other,denied));
  var yes=new BizContext("M",Set.of(),Set.of(),Set.of("ST"),"ST",false,Map.of("ST",Set.of("ACCOUNTANT")),Map.of("ST",Set.of(BizPerms.FINANCE)));
  var wrong=new BizContext("M",Set.of(),Set.of(),Set.of("OTHER"),"OTHER",true);
  var no=new BizContext("M",Set.of(),Set.of(),Set.of("ST"),"ST",false,Map.of("ST",Set.of("CS")),Map.of("ST",Set.of()));
  when(identities.resolve("FIN","ST")).thenReturn(yes);when(identities.resolve("OTHER","ST")).thenReturn(wrong);when(identities.resolve("CS","ST")).thenReturn(no);
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","ST")).containsExactly("FIN");
 }
 @Test void finance_member_in_authorized_non_default_store_receives_notice(){
  var accounts=mock(MchAccountMapper.class);var identities=mock(BizIdentityResolver.class);var member=new MchAccount();member.setUserNo("FIN");
  when(accounts.selectList(any())).thenReturn(List.of(member));
  var context=new BizContext("M",Set.of(),Set.of(),Set.of("DEFAULT","TARGET"),"DEFAULT",false,
   Map.of("DEFAULT",Set.of("CS"),"TARGET",Set.of("ACCOUNTANT")),Map.of("DEFAULT",Set.of(),"TARGET",Set.of(BizPerms.FINANCE)));
  when(identities.resolve("FIN","TARGET")).thenReturn(context);
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","TARGET")).containsExactly("FIN");
 }
 @Test void default_store_finance_cannot_leak_to_unauthorized_target_store(){
  var accounts=mock(MchAccountMapper.class);var identities=mock(BizIdentityResolver.class);var member=new MchAccount();member.setUserNo("FIN");
  when(accounts.selectList(any())).thenReturn(List.of(member));
  var context=new BizContext("M",Set.of(),Set.of(),Set.of("DEFAULT","TARGET"),"DEFAULT",false,
   Map.of("DEFAULT",Set.of("ACCOUNTANT"),"TARGET",Set.of("CS")),Map.of("DEFAULT",Set.of(BizPerms.FINANCE),"TARGET",Set.of()));
  when(identities.resolve("FIN","TARGET")).thenReturn(context);
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","TARGET")).isEmpty();
 }
 @Test void owner_cannot_receive_for_other_subject_or_unowned_store(){
  var accounts=mock(MchAccountMapper.class);var identities=mock(BizIdentityResolver.class);var member=new MchAccount();member.setUserNo("OWNER");
  when(accounts.selectList(any())).thenReturn(List.of(member));
  when(identities.resolve("OWNER","TARGET")).thenReturn(new BizContext("OTHER",Set.of(),Set.of(),Set.of("TARGET"),"DEFAULT",true));
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","TARGET")).isEmpty();
  when(identities.resolve("OWNER","TARGET")).thenReturn(new BizContext("M",Set.of(),Set.of(),Set.of("DEFAULT"),"DEFAULT",true));
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","TARGET")).isEmpty();
 }
 @Test void owner_receives_for_owned_non_default_store(){
  var accounts=mock(MchAccountMapper.class);var identities=mock(BizIdentityResolver.class);var member=new MchAccount();member.setUserNo("OWNER");
  when(accounts.selectList(any())).thenReturn(List.of(member));
  when(identities.resolve("OWNER","TARGET")).thenReturn(new BizContext("M",Set.of(),Set.of(),Set.of("DEFAULT","TARGET"),"DEFAULT",true));
  assertThat(new StoreFinanceRecipientsPortImpl(accounts,identities).recipients("M","TARGET")).containsExactly("OWNER");
 }
}
