package ai.neargo.shop.spi.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("范围编码：BRAND 是连锁（GROUP）的历史编码")
class ScopeCodesTest {

    @Test
    void brandDecodesToGroupAndBack() {
        assertThat(ScopeCodes.decode("BRAND")).isEqualTo(ScopeType.GROUP);
        assertThat(ScopeCodes.encode(ScopeType.GROUP)).isEqualTo("BRAND");
    }

    @Test
    void otherTypesKeepTheirNames() {
        for (ScopeType t : new ScopeType[] {ScopeType.STORE, ScopeType.ENTITY, ScopeType.PLATFORM}) {
            assertThat(ScopeCodes.decode(ScopeCodes.encode(t))).isEqualTo(t);
            assertThat(ScopeCodes.encode(t)).isEqualTo(t.name());
        }
    }

    @Test
    void groupIsNeverWrittenUnderItsDomainName() {
        assertThatThrownBy(() -> ScopeCodes.decode("GROUP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScopeCodes.decode("CHAIN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScopeCodes.decode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refCarriesTheStorageCode() {
        ScopeRef ref = ScopeRef.of("BRAND", "B1");
        assertThat(ref.type()).isEqualTo(ScopeType.GROUP);
        assertThat(ref.code()).isEqualTo("BRAND");
        assertThat(ref.no()).isEqualTo("B1");
    }
}
