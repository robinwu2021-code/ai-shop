package ai.neargo.shop.unit;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.payclient.StoredValueMerchantQueryAppService;
import ai.neargo.shop.paybridge.StoredValueMemberSelectionService;
import ai.neargo.shop.paybridge.dto.StoredValueCustomerViews.Source;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.user.PersonPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueMemberSelectionTest {
    final MemberService members=mock(MemberService.class);
    final PersonPort people=mock(PersonPort.class);
    final MemberCodeResolveService codes=mock(MemberCodeResolveService.class);
    final StoredValueMerchantQueryAppService accounts=mock(StoredValueMerchantQueryAppService.class);
    final ai.neargo.shop.payclient.StoredValuePlanRechargeAppService direct=mock(ai.neargo.shop.payclient.StoredValuePlanRechargeAppService.class);
    final EntitlementContext ctx=new EntitlementContext("MAIN","OPERATOR","query","0".repeat(64));

    @Test void findsRegisteredMemberBeforeAnyStoredValueAccountExistsWithoutCreatingPeople() {
        var person=new PersonPort.PersonView("PERSON","0001","USER","138****0001");
        when(people.findByPhone("13800000001")).thenReturn(Optional.of(person));
        var member=new MbrMember();member.setMemberNo("MEMBER");member.setPersonNo("PERSON");member.setStatus("ACTIVE");member.setRemark("林女士");
        when(members.find("ENTITY","PERSON")).thenReturn(Optional.of(member));
        when(accounts.directory(any(),eq("STORE"),eq(List.of("PERSON")),anyString(),isNull(),eq(1))).thenReturn(new CursorPage<>(List.of(),null));
        var service=new StoredValueMemberSelectionService(members,people,codes,accounts,direct);
        var result=service.candidates(ctx,"ENTITY","STORE","13800000001");
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().memberNo()).isEqualTo("MEMBER");
        assertThat(result.records().getFirst().canRecharge()).isTrue();
        assertThat(result.records().getFirst().account()).isNull();
        verify(people,never()).resolveOrCreateByPhone(anyString());
    }
    @Test void refusesSelectingMemberOutsideMerchantBeforeIssuingReference() {
        when(members.detail("ENTITY","FOREIGN")).thenReturn(Optional.empty());
        var service=new StoredValueMemberSelectionService(members,people,codes,accounts,direct);
        assertThatThrownBy(()->service.select(ctx,"ENTITY","STORE",new Source("MEMBER","FOREIGN",null))).isInstanceOf(BizException.class);
        verifyNoInteractions(codes,accounts);
    }
}
