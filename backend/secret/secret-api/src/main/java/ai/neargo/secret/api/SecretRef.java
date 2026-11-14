package ai.neargo.secret.api;

/**
 * 一段密文的引用（{@code sec_secret_blob.id}）。业务表只存这个 id —— 例如 {@code pay_onboarding_material.sec_secret_blob_id}。
 *
 * <p>引用本身不是秘密，可以落库、可以出现在日志里；它指向的明文只有 {@link SecretVault#open} 能拿到。
 */
public record SecretRef(long blobId) {

    public SecretRef {
        if (blobId <= 0) {
            throw new IllegalArgumentException("blobId must be positive");
        }
    }
}
