package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.StaffPartyReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.VersionReq;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbDiningDevice;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbIdentityAuthorization;
import ai.neargo.shop.food.entity.FnbIdentityOperation;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.AuthorizationMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.DeviceMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.OperationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.spi.food.DiningDeviceChanged;
import ai.neargo.shop.spi.food.DiningSessionChanged;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Restaurant identities never create platform users or confer payer authority. Raw secrets are
 * returned once and only hashes persist.
 */
public class DiningIdentityServiceImpl implements DiningIdentityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DeviceMapper devices;
    private final AuthorizationMapper authorizations;
    private final OperationMapper operations;
    private final GuestSessionMapper sessions;
    private final CheckMapper checks;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public DiningIdentityServiceImpl(
            DeviceMapper devices,
            AuthorizationMapper authorizations,
            OperationMapper operations,
            GuestSessionMapper sessions,
            CheckMapper checks,
            OutboxPublisher outbox,
            PlatformTransactionManager manager,
            ObjectMapper json,
            Clock clock) {
        this.devices = devices;
        this.authorizations = authorizations;
        this.operations = operations;
        this.sessions = sessions;
        this.checks = checks;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public DeviceVO device(String e, String s, String no) {
        scope(e, s, no);
        return scoped(() -> view(deviceRow(e, s, no, false)));
    }

    @Override
    public DeviceCredentialVO registerDevice(
            String e, String s, String actor, RegisterDeviceReq req, String key) {
        scope(e, s, actor);
        if (req == null) {
            bad();
        }
        id(req.deviceNo());
        version(req.version());
        text(req.displayName(), 64);
        var raw = new AtomicReference<String>();
        DeviceVO result =
                command(
                        e,
                        s,
                        actor,
                        "REGISTER_DEVICE",
                        key,
                        req,
                        DeviceVO.class,
                        () -> {
                            var row =
                                    devices.selectOne(
                                            Wrappers.<FnbDiningDevice>lambdaQuery()
                                                    .eq(FnbDiningDevice::getEntityNo, e)
                                                    .eq(FnbDiningDevice::getStoreNo, s)
                                                    .eq(
                                                            FnbDiningDevice::getDeviceNo,
                                                            req.deviceNo())
                                                    .last("FOR UPDATE"));
                            if (row == null) {
                                if (req.version() != 0) {
                                    conflict();
                                }
                                row = new FnbDiningDevice();
                                init(row, actor);
                                row.setEntityNo(e);
                                row.setStoreNo(s);
                                row.setDeviceNo(req.deviceNo());
                                row.setGeneration(1L);
                            } else {
                                if (!req.version().equals(row.getVersion())) {
                                    conflict();
                                }
                                row.setGeneration(Math.addExact(row.getGeneration(), 1));
                            }
                            String credential = random("fd_");
                            row.setCredentialHash(hash(credential));
                            row.setDisplayName(req.displayName());
                            row.setStatus("ACTIVE");
                            if (row.getId() == null) {
                                devices.insert(row);
                            } else {
                                stamp(row, actor);
                                if (devices.updateById(row) != 1) {
                                    conflict();
                                }
                            }
                            publish(row, "REGISTERED", actor);
                            raw.set(credential);
                            return view(row);
                        });
        return new DeviceCredentialVO(result, raw.get());
    }

    @Override
    public DeviceVO revokeDevice(
            String e, String s, String actor, String no, VersionReq req, String key) {
        scope(e, s, actor);
        id(no);
        validate(req);
        return command(
                e,
                s,
                actor,
                "REVOKE_DEVICE",
                key,
                List.of(no, req),
                DeviceVO.class,
                () -> {
                    var row = deviceRow(e, s, no, true);
                    if (!req.version().equals(row.getVersion())) {
                        conflict();
                    }
                    row.setStatus("REVOKED");
                    row.setGeneration(Math.addExact(row.getGeneration(), 1));
                    stamp(row, actor);
                    if (devices.updateById(row) != 1) {
                        conflict();
                    }
                    publish(row, "REVOKED", actor);
                    return view(row);
                });
    }

    @Override
    public SessionCredentialVO createDeviceSession(
            String e, String s, String no, String credential, DeviceSessionReq req, String key) {
        scope(e, s, no);
        text(credential, 128);
        if (req == null) {
            bad();
        }
        lifetime(req.lifetimeSeconds());
        var raw = new AtomicReference<String>();
        PartyVO party =
                command(
                        e,
                        s,
                        no,
                        "DEVICE_SESSION",
                        key,
                        List.of(no, HexFormat.of().formatHex(hash(credential)), req),
                        PartyVO.class,
                        () -> {
                            var device = deviceRow(e, s, no, true);
                            if (!"ACTIVE".equals(device.getStatus())
                                    || !MessageDigest.isEqual(
                                            device.getCredentialHash(), hash(credential))) {
                                forbidden();
                            }
                            return create(
                                    e,
                                    s,
                                    no,
                                    null,
                                    device,
                                    device.getDisplayName(),
                                    "CART_DEVICE",
                                    req.lifetimeSeconds(),
                                    raw);
                        });
        return new SessionCredentialVO(party, raw.get());
    }

    @Override
    public SessionCredentialVO createStaffParty(
            String e, String s, String actor, StaffPartyReq req, String key) {
        scope(e, s, actor);
        if (req == null) {
            bad();
        }
        text(req.displayName(), 64);
        lifetime(req.lifetimeSeconds());
        if (req.checkNo() != null) {
            id(req.checkNo());
        }
        var raw = new AtomicReference<String>();
        PartyVO party =
                command(
                        e,
                        s,
                        actor,
                        "STAFF_PARTY",
                        key,
                        req,
                        PartyVO.class,
                        () -> {
                            if (req.checkNo() != null
                                    && checks.selectOne(
                                                    Wrappers.<FnbCheck>lambdaQuery()
                                                            .eq(FnbCheck::getEntityNo, e)
                                                            .eq(FnbCheck::getStoreNo, s)
                                                            .eq(FnbCheck::getCheckNo, req.checkNo())
                                                            .eq(FnbCheck::getStatus, "OPEN")
                                                            .last("FOR UPDATE"))
                                            == null) {
                                forbidden();
                            }
                            return create(
                                    e,
                                    s,
                                    actor,
                                    req.checkNo(),
                                    null,
                                    req.displayName(),
                                    "STAFF_PARTY",
                                    req.lifetimeSeconds(),
                                    raw);
                        });
        return new SessionCredentialVO(party, raw.get());
    }

    @Override
    public PartyVO revokeParty(
            String e, String s, String actor, String no, VersionReq req, String key) {
        scope(e, s, actor);
        id(no);
        validate(req);
        return command(
                e,
                s,
                actor,
                "REVOKE_PARTY",
                key,
                List.of(no, req),
                PartyVO.class,
                () -> {
                    var session = session(no, true);
                    if (!e.equals(session.getEntityNo()) || !s.equals(session.getStoreNo())) {
                        forbidden();
                    }
                    var grant = authorization(e, s, no, true);
                    if (grant == null || !actor.equals(grant.getIssuedBy())) {
                        forbidden();
                    }
                    if (!req.version().equals(session.getVersion())) {
                        conflict();
                    }
                    session.setRevokedAt(now());
                    stamp(session, actor);
                    if (sessions.updateById(session) != 1) {
                        conflict();
                    }
                    grant.setStatus("REVOKED");
                    stamp(grant, actor);
                    if (authorizations.updateById(grant) != 1) {
                        conflict();
                    }
                    sessionEvent(session, "PARTY_REVOKED");
                    return view(session, grant);
                });
    }

    @Override
    public PartyVO party(String e, String s, String no) {
        scope(e, s, no);
        return scoped(
                () ->
                        tx.execute(
                                t -> {
                                    var session = session(no, true);
                                    if (!e.equals(session.getEntityNo())
                                            || !s.equals(session.getStoreNo())) {
                                        forbidden();
                                    }
                                    return liveParty(session);
                                }));
    }

    @Override
    public void validateDeviceSession(String no) {
        id(no);
        scoped(
                () ->
                        tx.execute(
                                t -> {
                                    var session = session(no, true);
                                    if (!scopes(session).contains("CART_DEVICE")) {
                                        forbidden();
                                    }
                                    liveParty(session);
                                    return null;
                                }));
    }

    private PartyVO create(
            String e,
            String s,
            String actor,
            String check,
            FnbDiningDevice device,
            String name,
            String scope,
            int lifetime,
            AtomicReference<String> raw) {
        String secret = random("fs_");
        var session = new FnbGuestSession();
        init(session, actor);
        session.setEntityNo(e);
        session.setStoreNo(s);
        session.setSessionNo(BizKey.next("FGSS"));
        session.setCheckNo(check);
        session.setBuyerNo(null);
        session.setDeviceNo(device == null ? null : device.getDeviceNo());
        session.setTokenHash(hash(secret));
        session.setExpiresAt(now().plusSeconds(lifetime));
        session.setScope(json.writeValueAsString(List.of(scope)));
        sessions.insert(session);
        var grant = new FnbIdentityAuthorization();
        init(grant, actor);
        grant.setEntityNo(e);
        grant.setStoreNo(s);
        grant.setAuthorizationNo(BizKey.next("FIA"));
        grant.setSessionNo(session.getSessionNo());
        grant.setKind(device == null ? "GUEST" : "DEVICE");
        grant.setDeviceNo(session.getDeviceNo());
        grant.setDeviceGeneration(device == null ? 0L : device.getGeneration());
        grant.setDisplayName(name);
        grant.setIssuedBy(actor);
        grant.setStatus("ACTIVE");
        authorizations.insert(grant);
        sessionEvent(session, scope + "_CREATED");
        raw.set(secret);
        return view(session, grant);
    }

    private PartyVO liveParty(FnbGuestSession session) {
        if (session.getRevokedAt() != null
                || session.getExpiresAt() == null
                || !session.getExpiresAt().isAfter(now())) {
            forbidden();
        }
        var scope = scopes(session);
        var grant =
                authorization(
                        session.getEntityNo(), session.getStoreNo(), session.getSessionNo(), true);
        if (scope.contains("CART_DEVICE")) {
            if (session.getBuyerNo() != null
                    || session.getCheckNo() != null
                    || session.getTableNo() != null) {
                forbidden();
            }
            if (grant == null
                    || !"ACTIVE".equals(grant.getStatus())
                    || !"DEVICE".equals(grant.getKind())
                    || !Objects.equals(session.getDeviceNo(), grant.getDeviceNo())) {
                forbidden();
            }
            var device =
                    deviceRow(
                            session.getEntityNo(),
                            session.getStoreNo(),
                            session.getDeviceNo(),
                            true);
            if (!"ACTIVE".equals(device.getStatus())
                    || !Objects.equals(device.getGeneration(), grant.getDeviceGeneration())) {
                forbidden();
            }
        } else if (scope.contains("STAFF_PARTY")
                && (grant == null
                        || !"ACTIVE".equals(grant.getStatus())
                        || !"GUEST".equals(grant.getKind()))) {
            forbidden();
        }
        return view(session, grant);
    }

    private PartyVO view(FnbGuestSession session, FnbIdentityAuthorization grant) {
        return new PartyVO(
                grant == null ? "GUEST" : grant.getKind(),
                session.getEntityNo(),
                session.getStoreNo(),
                session.getSessionNo(),
                session.getDeviceNo(),
                grant == null ? null : grant.getDisplayName(),
                session.getExpiresAt().toInstant(ZoneOffset.UTC),
                scopes(session),
                session.getCheckNo(),
                session.getBuyerNo(),
                grant == null ? 0 : grant.getDeviceGeneration(),
                session.getVersion());
    }

    private DeviceVO view(FnbDiningDevice device) {
        return new DeviceVO(
                device.getEntityNo(),
                device.getStoreNo(),
                device.getDeviceNo(),
                device.getDisplayName(),
                device.getStatus(),
                device.getGeneration(),
                device.getVersion());
    }

    private List<String> scopes(FnbGuestSession row) {
        var node = json.readTree(row.getScope());
        if (!node.isArray()) {
            forbidden();
        }
        var scope = new ArrayList<String>();
        for (var n : node) {
            if (!n.isString()) {
                forbidden();
            }
            scope.add(n.asString());
        }
        return List.copyOf(scope);
    }

    private FnbDiningDevice deviceRow(String e, String s, String no, boolean lock) {
        var row =
                devices.selectOne(
                        Wrappers.<FnbDiningDevice>lambdaQuery()
                                .eq(FnbDiningDevice::getEntityNo, e)
                                .eq(FnbDiningDevice::getStoreNo, s)
                                .eq(FnbDiningDevice::getDeviceNo, no)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private FnbGuestSession session(String no, boolean lock) {
        var row =
                sessions.selectOne(
                        Wrappers.<FnbGuestSession>lambdaQuery()
                                .eq(FnbGuestSession::getSessionNo, no)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private FnbIdentityAuthorization authorization(String e, String s, String no, boolean lock) {
        return authorizations.selectOne(
                Wrappers.<FnbIdentityAuthorization>lambdaQuery()
                        .eq(FnbIdentityAuthorization::getEntityNo, e)
                        .eq(FnbIdentityAuthorization::getStoreNo, s)
                        .eq(FnbIdentityAuthorization::getSessionNo, no)
                        .last(lock ? "FOR UPDATE" : ""));
    }

    private <T> T command(
            String e,
            String s,
            String actor,
            String action,
            String key,
            Object payload,
            Class<T> type,
            Supplier<T> work) {
        text(key, 128);
        String keyHash = FoodCommandFingerprint.hash(key),
                requestHash = FoodCommandFingerprint.hash(action, json.writeValueAsString(payload));
        return scoped(
                () -> {
                    try {
                        return tx.execute(
                                t -> {
                                    var old =
                                            operations.selectOne(
                                                    Wrappers.<FnbIdentityOperation>lambdaQuery()
                                                            .eq(
                                                                    FnbIdentityOperation
                                                                            ::getEntityNo,
                                                                    e)
                                                            .eq(FnbIdentityOperation::getStoreNo, s)
                                                            .eq(
                                                                    FnbIdentityOperation
                                                                            ::getActorNo,
                                                                    actor)
                                                            .eq(
                                                                    FnbIdentityOperation::getAction,
                                                                    action)
                                                            .eq(
                                                                    FnbIdentityOperation
                                                                            ::getRequestKeyHash,
                                                                    keyHash));
                                    if (old != null) {
                                        if (!requestHash.equals(old.getRequestHash())
                                                || old.getResultJson() == null) {
                                            conflict();
                                        }
                                        return json.readValue(old.getResultJson(), type);
                                    }
                                    var operation = new FnbIdentityOperation();
                                    init(operation, actor);
                                    operation.setEntityNo(e);
                                    operation.setStoreNo(s);
                                    operation.setOperationNo(BizKey.next("FIO"));
                                    operation.setActorNo(actor);
                                    operation.setAction(action);
                                    operation.setRequestKeyHash(keyHash);
                                    operation.setRequestHash(requestHash);
                                    operations.insert(operation);
                                    T result = work.get();
                                    operation.setResultJson(json.writeValueAsString(result));
                                    stamp(operation, actor);
                                    if (operations.updateById(operation) != 1) {
                                        conflict();
                                    }
                                    return result;
                                });
                    } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                });
    }

    private void publish(FnbDiningDevice d, String action, String actor) {
        outbox.publish(
                new DiningDeviceChanged(
                        d.getEntityNo(),
                        d.getStoreNo(),
                        d.getDeviceNo(),
                        action,
                        d.getVersion(),
                        d.getGeneration(),
                        BizKey.next("FIDE")),
                new PublishOptions("FOOD_DEVICE:" + d.getDeviceNo(), 1));
    }

    private void sessionEvent(FnbGuestSession s, String action) {
        outbox.publish(
                new DiningSessionChanged(
                        s.getEntityNo(),
                        s.getStoreNo(),
                        s.getTableNo(),
                        s.getSessionNo(),
                        action,
                        s.getVersion(),
                        BizKey.next("FIDE")),
                new PublishOptions("FOOD_GUEST_SESSION:" + s.getSessionNo(), 1));
    }

    private void init(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedBy(actor);
        row.setCreatedAt(now());
        stamp(row, actor);
    }

    private void stamp(BaseEntity row, String actor) {
        row.setUpdatedBy(actor);
        row.setUpdatedAt(now());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static String random(String prefix) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> T scoped(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(work);
    }

    private static void scope(String e, String s, String a) {
        id(e);
        id(s);
        id(a);
    }

    private static void id(String v) {
        if (v == null || !v.matches("[A-Za-z0-9_.:-]{1,32}")) {
            bad();
        }
    }

    private static void text(String v, int max) {
        if (v == null || v.isBlank() || v.length() > max || v.chars().anyMatch(c -> c < 32)) {
            bad();
        }
    }

    private static void version(Long v) {
        if (v == null || v < 0 || v == Long.MAX_VALUE) {
            bad();
        }
    }

    private static void validate(VersionReq r) {
        if (r == null) {
            bad();
        }
        version(r.version());
        text(r.reason(), 255);
    }

    private static void lifetime(int v) {
        if (v < 60 || v > 86400) {
            bad();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void forbidden() {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
