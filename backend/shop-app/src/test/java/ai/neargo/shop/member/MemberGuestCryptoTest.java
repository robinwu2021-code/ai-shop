package ai.neargo.shop.member;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberGuestCrypto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciphertext copied across tenant/owner/store/guest/purpose boundaries must never decrypt. */
class MemberGuestCryptoTest {
    @Test void randomizedAuthenticatedEncryptionSupportsUtf8AndBindsEveryOwnerField() {
        var cipher = new MemberGuestCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String contact = "\u987e".repeat(128);
        String encrypted = cipher.encrypt("MAIN", "E", "S", "G", "contact", contact);
        assertThat(encrypted).doesNotContain(contact);
        assertThat(cipher.encrypt("MAIN", "E", "S", "G", "contact", contact)).isNotEqualTo(encrypted);
        assertThat(cipher.decrypt("MAIN", "E", "S", "G", "contact", encrypted)).isEqualTo(contact);
        for (String[] changed : new String[][]{{"OTHER", "E", "S", "G", "contact"},
                {"MAIN", "X", "S", "G", "contact"}, {"MAIN", "E", "X", "G", "contact"},
                {"MAIN", "E", "S", "X", "contact"}, {"MAIN", "E", "S", "G", "claim-token"}}) {
            assertThatThrownBy(() -> cipher.decrypt(changed[0], changed[1], changed[2], changed[3],
                    changed[4], encrypted))
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.MEMBER_GUEST_UNAVAILABLE));
        }
        assertThatThrownBy(() -> cipher.decrypt("MAIN", "E", "S", "G", "contact", "v1.AAAA"))
                .isInstanceOf(BizException.class);
    }
    @Test void absentMalformedOrWrongSizedKeysDisableCapabilityInsteadOfInventingDefault() {
        for (String key : new String[]{"", "not-base64", "AAAA"}) {
            var cipher = new MemberGuestCrypto(key);
            assertThatThrownBy(cipher::requireAvailable).isInstanceOfSatisfying(BizException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.MEMBER_GUEST_UNAVAILABLE));
        }
    }
}
