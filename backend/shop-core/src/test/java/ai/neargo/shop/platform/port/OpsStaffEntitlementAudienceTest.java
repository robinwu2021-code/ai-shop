package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.platform.perm.RolePermResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Entity notices must apply the same merchant dimension as the authenticated operator APIs. */
class OpsStaffEntitlementAudienceTest {
    @Test void readPermissionAloneCannotCrossMerchantOrInferMerchantFromCommunityScope() {
        var mapper=mock(StaffMapper.class); var roles=mock(RolePermResolver.class);
        var m1=staff("M1-READ","READ","M1",null);
        var m2=staff("M2-READ","READ","M2",null);
        var global=staff("GLOBAL","GLOBAL","M2",null);
        var community=staff("COMMUNITY","READ",null,"C1");
        var denied=staff("NO-PERM","NONE","M1",null);
        when(mapper.selectList(any())).thenReturn(List.of(m1,m2,global,community,denied));
        when(roles.of(List.of("READ"))).thenReturn(List.of(Perms.ENT_EXCEPTION_READ));
        when(roles.of(List.of("GLOBAL"))).thenReturn(List.of("*"));
        when(roles.of(List.of("NONE"))).thenReturn(List.of());
        var owner=new OpsStaffPortImpl(mapper,roles,new ObjectMapper());
        assertThat(owner.staffNosWithPermForEntity(Perms.ENT_EXCEPTION_READ,"M1"))
                .containsExactly("M1-READ","GLOBAL");
        assertThat(owner.staffNosWithPermForEntity(Perms.ENT_EXCEPTION_READ,"M2"))
                .containsExactly("M2-READ","GLOBAL");
    }
    private SysOpsStaff staff(String no,String role,String merchant,String community) {
        var row=new SysOpsStaff(); row.setStaffNo(no); row.setRoles("[\""+role+"\"]");
        row.setTenantNo("MAIN"); row.setStatus("ACTIVE"); row.setMerchantNo(merchant); row.setCommunityNo(community);
        return row;
    }
}
