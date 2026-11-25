package ai.neargo.shop.platform.impl;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.observability.OperationLog;
import ai.neargo.shop.platform.OpsApiKeyService;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.platform.auth.OpsApiKeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsApiKeyServiceImpl implements OpsApiKeyService {

    private static final Pattern KEY_FORMAT = Pattern.compile(PREFIX + "[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final OpsApiKeyStore keys;
    private final OperatorIdentityLoader identities;
    private final OpsService ops;

    public OpsApiKeyServiceImpl(OpsApiKeyStore keys, OperatorIdentityLoader identities, OpsService ops) {
        this.keys = keys;
        this.identities = identities;
        this.ops = ops;
    }

    @Override
    @Transactional
    public Issued issue(String staffNo, UUID keyNo, String label, Access access, int validDays) {
        requireEligibleStaff(staffNo);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String apiKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var key = insert(staffNo, keyNo, label.strip(), access, Duration.ofDays(validDays).toMillis(),
                TokenHash.of(apiKey));
        return new Issued(key, apiKey);
    }

    @Override
    @Transactional
    @OperationLog("ops.api_key.register")
    public Metadata registerOwn(UUID keyNo, String label, Access access, int validDays, String hash) {
        String staffNo = SecurityUtils.currentUserNo();
        requireEligibleStaff(staffNo);
        long duration = Duration.ofDays(validDays).toMillis();
        String normalizedLabel = label.strip();
        var existing = keys.registration(staffNo, keyNo.toString()).orElse(null);
        if (existing == null) {
            return insert(staffNo, keyNo, normalizedLabel, access, duration, hash);
        }
        Metadata key = existing.key();
        if (!existing.secretHash().equals(hash) || !key.label().equals(normalizedLabel)
                || key.access() != access || key.expiresAt() - key.createdAt() != duration) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return key;
    }

    private void requireEligibleStaff(String staffNo) {
        if (!keys.lockEligibleStaff(staffNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private Metadata insert(String staffNo, UUID keyNo, String label, Access access, long duration, String hash) {
        long now = System.currentTimeMillis();
        var key = new Metadata(keyNo.toString(), label, access, SecurityUtils.currentUserNo(),
                now, now + duration, null);
        try {
            keys.insert(staffNo, hash, key);
        } catch (DuplicateKeyException duplicate) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        ops.audit("OPS_API_KEY_ISSUE", staffNo + ":" + keyNo, access.name(), true, null, null);
        return key;
    }

    @Override
    public List<Metadata> list(String staffNo) {
        return keys.list(staffNo);
    }

    @Override
    @Transactional
    @OperationLog("ops.api_key.revoke")
    public void revoke(String staffNo, UUID keyNo) {
        if (keys.revoke(staffNo, keyNo.toString(), System.currentTimeMillis())) {
            ops.audit("OPS_API_KEY_REVOKE", staffNo + ":" + keyNo, null, true, null, null);
        }
    }

    @Override
    public Optional<Identity> authenticate(String apiKey) {
        if (!KEY_FORMAT.matcher(apiKey).matches()) {
            return Optional.empty();
        }
        return keys.activeBinding(TokenHash.of(apiKey), System.currentTimeMillis())
                .flatMap(key -> identities.load(key.staffNo())
                        .map(user -> new Identity(key.keyNo(), key.access(), user)));
    }
}
