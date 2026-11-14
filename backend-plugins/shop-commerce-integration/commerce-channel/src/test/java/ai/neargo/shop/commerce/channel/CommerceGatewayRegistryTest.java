package ai.neargo.shop.commerce.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.commerce.CommerceGateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class CommerceGatewayRegistryTest {
    @Test
    void noDefaultSuccessGatewayExists() {
        assertThat(new CommerceGatewayRegistry(List.of()).find("KEETA_AE")).isEmpty();
    }

    @Test
    void duplicateProfilesFailAtStartup() {
        CommerceGateway gateway =
                new CommerceGateway() {
                    public String profileNo() {
                        return "TEST";
                    }

                    public Set<Capability> capabilities() {
                        return Set.of();
                    }
                };
        assertThatThrownBy(() -> new CommerceGatewayRegistry(List.of(gateway, gateway)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new CommerceGatewayRegistry(List.of(gateway)).find("TEST")).contains(gateway);
    }
}
