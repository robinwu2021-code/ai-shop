package ai.neargo.shop.payclient;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.*;
import ai.neargo.shop.payclient.impl.PointsConfigurationAppServiceImpl;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PointsConfigurationAuditTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void name_change_records_system_log_with_before_and_after_without_a_remark() {
        var domain = mock(PointsAdministrationService.class);
        var audit = mock(AuditLogPort.class);
        var app = new PointsConfigurationAppServiceImpl(domain, audit);
        var principal = LoginUser.operator("OPS", "Operator", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var request = new TypeRename("R1", "HX_TOKEN", "平台积分", "虹选积分", null);
        var result = new ChangeResult("R1", "RENAME", "HX_TOKEN", 100);
        when(domain.rename(request, "OPS")).thenReturn(result);
        assertThat(app.rename(request, "OPS")).isEqualTo(result);
        verify(audit).record(eq("POINTS_RENAME"), eq("HX_TOKEN"), contains("R1"), eq(false), contains("平台积分"), contains("虹选积分"));
    }
    @Test void lifecycle_change_is_automatically_written_to_the_system_audit() {
        var domain = mock(PointsAdministrationService.class);
        var audit = mock(AuditLogPort.class);
        var app = new PointsConfigurationAppServiceImpl(domain, audit);
        var principal = LoginUser.operator("OPS", "Operator", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var request = new TypeChange("S1", "HX_TOKEN", TypeAction.PAUSE_EARNING, null);
        var result = new ChangeResult("S1", "TYPE_STATE", "HX_TOKEN", 200);
        when(domain.transition(request, "OPS")).thenReturn(result);
        assertThat(app.transition(request, "OPS")).isEqualTo(result);
        verify(audit).record(eq("POINTS_TYPE_STATE"), eq("HX_TOKEN"), contains("S1"), eq(true), isNull(), contains("PAUSE_EARNING"));
    }
}
