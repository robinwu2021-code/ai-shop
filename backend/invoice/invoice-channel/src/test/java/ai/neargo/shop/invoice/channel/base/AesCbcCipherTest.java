package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** AES-CBC：与 openssl 独立算出的密文逐字节一致；先 URL Encode 再加密（绿界做法）；密钥配错算账号问题。 */
class AesCbcCipherTest {

    /**
     * {@code printf '%s' "$(urlencode '{"a":"中文 & x"}')" | openssl enc -aes-128-cbc -K hex(ejCk326UnaZWKisg)
     * -iv hex(q9jcZX8Ib9LM8wYk) -base64 -A}
     */
    static final String OPENSSL = "vXoKIYL6ELJaiX9JmSN5ARt92S19kDRSW5mggIp5VHLM4SSZ3zvnEEyIl2H9nKhH";

    @Test
    void ac41MatchesOpensslAndRoundTrips() {
        var cipher = new AesCbcCipher("ejCk326UnaZWKisg", "q9jcZX8Ib9LM8wYk", true);
        assertEquals(OPENSSL, cipher.encrypt("{\"a\":\"中文 & x\"}"));
        assertEquals("{\"a\":\"中文 & x\"}", cipher.decrypt(OPENSSL));
    }

    @Test
    void ac41WrongKeyLengthIsAnAccountProblem() {
        assertThrows(AbstractInvoiceGateway.AccountMisconfiguredException.class,
                () -> new AesCbcCipher("short", "q9jcZX8Ib9LM8wYk", true).encrypt("x"));
        assertThrows(AbstractInvoiceGateway.AccountMisconfiguredException.class,
                () -> new AesCbcCipher("ejCk326UnaZWKisg", "q9jcZX8Ib9LM8wYk", true).decrypt("%%%"));
    }
}
