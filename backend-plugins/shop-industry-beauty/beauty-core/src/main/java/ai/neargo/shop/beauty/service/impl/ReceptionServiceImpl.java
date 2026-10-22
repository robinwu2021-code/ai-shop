package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.entity.BtyArrivalChallenge;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.idem.IdempotencyScope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.dao.DuplicateKeyException;

/** Public store challenges are short lived, durable on their original key, and never published to an event log. */
public final class ReceptionServiceImpl implements ReceptionService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final BeautyMappers.ArrivalChallengeMapper challenges;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public ReceptionServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.ArrivalChallengeMapper challenges, Clock clock) {
        this.db = db; this.access = access; this.challenges = challenges; this.clock = clock;
    }
    @Override
    public ArrivalChallengeVO issueArrivalChallenge(String entity, String store, String actor, String key) {
        access.merchant(entity, store, actor, true);
        String hash = IdempotencyScope.requestHash(IdempotencyScope.requireKey(key));
        var existing = find(entity, store, hash);
        if (existing != null) { return view(existing); }
        for (int attempt = 0; attempt < 3; attempt++) {
            var user = SecurityUtils.requireUser();
            var row = new BtyArrivalChallenge(); row.setEntityNo(entity); row.setStoreNo(store);
            row.setChallengeNo(BizKey.next("BTAC")); row.setActorNo(actor); row.setActorRealm(user.realm().name());
            row.setActorKind(user.subjectKind().name()); row.setKeyHash(hash);
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            row.setCode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            row.setIssuedAt(LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
            row.setExpiresAt(row.getIssuedAt().plusSeconds(90));
            try { db.tx(() -> { challenges.insert(row); return null; }); return view(row); }
            catch (DuplicateKeyException raced) {
                var winner = find(entity, store, hash);
                if (winner != null) { return view(winner); }
                if (attempt == 2) { throw raced; }
            }
        }
        throw new IllegalStateException("Arrival challenge collision budget exhausted");
    }
    private BtyArrivalChallenge find(String entity, String store, String hash) {
        var user = SecurityUtils.requireUser();
        return db.read(() -> challenges.selectOne(Wrappers.<BtyArrivalChallenge>lambdaQuery()
                .eq(BtyArrivalChallenge::getTenantNo, user.tenantNo()).eq(BtyArrivalChallenge::getEntityNo, entity)
                .eq(BtyArrivalChallenge::getStoreNo, store).eq(BtyArrivalChallenge::getActorNo, user.userNo())
                .eq(BtyArrivalChallenge::getActorRealm, user.realm().name())
                .eq(BtyArrivalChallenge::getActorKind, user.subjectKind().name())
                .eq(BtyArrivalChallenge::getKeyHash, hash)));
    }
    private ArrivalChallengeVO view(BtyArrivalChallenge row) {
        return new ArrivalChallengeVO(row.getChallengeNo(), row.getStoreNo(), row.getCode(),
                row.getIssuedAt().atOffset(ZoneOffset.UTC), row.getExpiresAt().atOffset(ZoneOffset.UTC));
    }
}
