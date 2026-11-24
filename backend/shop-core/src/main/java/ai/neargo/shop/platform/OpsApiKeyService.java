package ai.neargo.shop.platform;

import ai.neargo.shop.auth.LoginUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OpsApiKeyService {

    String PREFIX = "oak_";

    enum Access { READ_ONLY, MANAGE }

    record Metadata(String keyNo, String label, Access access, String issuedBy,
                    long createdAt, long expiresAt, Long revokedAt) {}

    enum Status { ACTIVE, EXPIRED, REVOKED }

    record SelfMetadata(String keyNo, String label, Access access, String issuedBy,
                        long createdAt, long expiresAt, Long revokedAt, Status status) {
        public static SelfMetadata of(Metadata key, long now) {
            Status status = key.revokedAt() != null ? Status.REVOKED
                    : key.expiresAt() <= now ? Status.EXPIRED : Status.ACTIVE;
            return new SelfMetadata(key.keyNo(), key.label(), key.access(), key.issuedBy(), key.createdAt(),
                    key.expiresAt(), key.revokedAt(), status);
        }
    }

    record Issued(Metadata key, String apiKey) {
        @Override
        public String toString() {
            return "Issued[key=" + key + "]";
        }
    }

    record Identity(String keyNo, Access access, LoginUser user) {}

    Issued issue(String staffNo, UUID keyNo, String label, Access access, int validDays);

    Metadata registerOwn(UUID keyNo, String label, Access access, int validDays, String secretHash);

    List<Metadata> list(String staffNo);

    void revoke(String staffNo, UUID keyNo);

    Optional<Identity> authenticate(String apiKey);
}
