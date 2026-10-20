package ai.neargo.shop.pay.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.migration.service.LegacySnapshotCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LegacySnapshotCipherTest {
    @Test void authenticationBindsOriginalEvidenceAndRejectsTamperingAndMissingKey() {
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        var cipher=new LegacySnapshotCipher(key,"TEST");
        String aad="MAIN|M1|PMT|T1|B1|original-hash";
        String secret="legacy-original-code";
        var first=cipher.encrypt(secret,aad);
        var second=cipher.encrypt(secret,aad);
        assertThat(first).isNotEqualTo(second).doesNotContain(secret);
        assertThat(cipher.decrypt(first,aad)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher.decrypt(first,aad+"changed")).isInstanceOf(BizException.class);
        String[] pieces=first.split("\\.");
        pieces[3]=(pieces[3].startsWith("A")?"B":"A")+pieces[3].substring(1);
        assertThatThrownBy(() -> cipher.decrypt(String.join(".",pieces),aad)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new LegacySnapshotCipher("","").encrypt(secret,aad)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new LegacySnapshotCipher(key,"OTHER").decrypt(first,aad)).isInstanceOf(BizException.class);
    }
}
