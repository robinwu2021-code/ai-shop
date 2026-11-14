package ai.neargo.shop.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.user.entity.UsrTestIdentity;
import ai.neargo.shop.user.mapper.UserMappers.TestIdentityMapper;
import ai.neargo.shop.user.service.impl.TestIdentityServiceImpl;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestIdentityServiceTest {

    static TestIdentityMapper mapperWith(String type, String value) {
        var row = new UsrTestIdentity();
        row.setIdentityType(type);
        row.setIdentityValue(value);
        row.setEnabled(true);
        var m = mock(TestIdentityMapper.class);
        when(m.selectList(any())).thenReturn(List.of(row));
        return m;
    }

    @Test
    @DisplayName("★★★ 总开关关着：名单在也一律不认，连库都不查")
    void switchOffAcceptsNothing() {
        var m = mapperWith("PHONE", "+886900000102");
        var s = new TestIdentityServiceImpl(m, mock(AuditLogPort.class), false, "1234", "123456");
        assertThat(s.acceptsCode("PHONE", "+886900000102", "1234")).isFalse();
        assertThat(s.acceptsPassword("PHONE", "+886900000102", "123456")).isFalse();
        verify(m, never()).selectList(any());
    }

    @Test
    @DisplayName("★★ 开着：名单内认统一码与统一密码（手机号去空格连字符、邮箱忽略大小写），错的不认")
    void switchOnAcceptsOnlyListedAndExact() {
        var s = new TestIdentityServiceImpl(mapperWith("EMAIL", "robin@neargo.ai"), mock(AuditLogPort.class),
                true, "1234", "123456");
        assertThat(s.acceptsCode("EMAIL", " Robin@NearGo.ai ", "1234")).isTrue();
        assertThat(s.acceptsPassword("EMAIL", "robin@neargo.ai", "123456")).isTrue();
        assertThat(s.acceptsCode("EMAIL", "robin@neargo.ai", "12345")).isFalse();
        assertThat(s.acceptsPassword("EMAIL", "robin@neargo.ai", "1234")).isFalse();
        assertThat(s.acceptsCode("EMAIL", "other@neargo.ai", "1234")).isFalse();
        assertThat(s.acceptsCode("PHONE", "robin@neargo.ai", "1234")).isFalse();

        var p = new TestIdentityServiceImpl(mapperWith("PHONE", "+886900000102"), mock(AuditLogPort.class),
                true, "1234", "123456");
        assertThat(p.acceptsCode("PHONE", "+886 900-000-102", "1234")).isTrue();
    }

    @Test
    @DisplayName("开着却没配统一码或密码：拒绝启动，不让空串当成「人人都对」")
    void enabledWithoutSecretsRefusesToStart() {
        assertThatThrownBy(() -> new TestIdentityServiceImpl(mock(TestIdentityMapper.class), mock(AuditLogPort.class),
                true, "", "123456")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TestIdentityServiceImpl(mock(TestIdentityMapper.class), mock(AuditLogPort.class),
                true, "1234", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalize() {
        assertThat(TestIdentityService.normalize("PHONE", "+886 912-345-633")).isEqualTo("+886912345633");
        assertThat(TestIdentityService.normalize("PHONE", "abc")).isNull();
        assertThat(TestIdentityService.normalize("EMAIL", "A@B.co")).isEqualTo("a@b.co");
        assertThat(TestIdentityService.normalize("EMAIL", "nope")).isNull();
        assertThat(TestIdentityService.normalize("WX", "x")).isNull();
    }
}
