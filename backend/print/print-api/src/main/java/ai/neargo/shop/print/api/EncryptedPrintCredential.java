package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record EncryptedPrintCredential(
        String credentialRef, String encryptionKeyVersion, byte[] ciphertext) {
    public EncryptedPrintCredential {
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
