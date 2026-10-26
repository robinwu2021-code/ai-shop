package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChannelIdentityTest {
    @Test
    void globalStoreIdentityDoesNotDependOnAuthorization() {
        assertThat(ChannelIdentity.storeKey("p:TW:v1", false, "old-account", "Store 01"))
                .isEqualTo(ChannelIdentity.storeKey("p:TW:v1", false, "new-account", "Store 01"));
        assertThat(ChannelIdentity.storeKey("p:TW:v1", false, "a", "Store 01"))
                .isNotEqualTo(ChannelIdentity.storeKey("p:AE:v1", false, "a", "Store 01"));
    }

    @Test
    void accountScopedIdentityPreservesProtocolBoundaryAndCase() {
        assertThat(ChannelIdentity.storeKey("p", true, "a", "S"))
                .isNotEqualTo(ChannelIdentity.storeKey("p", true, "b", "S"))
                .isNotEqualTo(ChannelIdentity.storeKey("p", true, "a", "s"));
        assertThatThrownBy(() -> ChannelIdentity.storeKey("p", true, null, "S"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthPrefixedEncodingCannotCollideThroughSeparators() {
        assertThat(ChannelIdentity.storeKey("p", true, "a:b", "c"))
                .isNotEqualTo(ChannelIdentity.storeKey("p", true, "a", "b:c"));
    }
}
