package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.AccountView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ActivateCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationDetail;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationEvent;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ChannelCredentials;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviewCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.ReviseCommand;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.RevisionView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.RouteView;
import ai.neargo.shop.invoice.dto.InvoiceChannelDtos.SubmitCommand;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.gateway.AccountCapability;
import ai.neargo.shop.invoice.gateway.CredentialField;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.regime.tw.TwQrCipher;
import ai.neargo.shop.invoice.service.InvoiceChannelService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.print.api.PrintHashes;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvoiceChannelServiceImpl implements InvoiceChannelService {

    private static final String ACCOUNT_SNAPSHOT = """
            SELECT a.*, r.seller_name, r.provider_secret_ref, r.qr_key_ref, r.verified_at
              FROM ivc_channel_account a JOIN ivc_channel_account_revision r
                ON r.tenant_no=a.tenant_no AND r.account_no=a.account_no AND r.revision_no=a.current_revision
             WHERE a.tenant_no=? AND a.entity_no=? AND a.environment=?
            """;

    private final JdbcTemplate jdbc;
    private final RegimeProfileMapper profiles;
    private final InvoiceGatewayPolicy gatewayPolicy;
    private final InvoiceGateways gateways;
    private final InvoiceRegimes regimes;
    private final InvoiceProviderAccounts credentials;
    private final ObjectProvider<SecretVault> vault;
    private final ObjectMapper json;
    private final String environment;

    public InvoiceChannelServiceImpl(JdbcTemplate jdbc, RegimeProfileMapper profiles, InvoiceGatewayPolicy gatewayPolicy,
            InvoiceGateways gateways, InvoiceRegimes regimes, InvoiceProviderAccounts credentials,
            ObjectProvider<SecretVault> vault, ObjectMapper json,
            @Value("${shop.invoice.channel.environment:}") String environment) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.gatewayPolicy = gatewayPolicy;
        this.gateways = gateways;
        this.regimes = regimes;
        this.credentials = credentials;
        this.vault = vault;
        this.json = json;
        this.environment = environment;
    }

    @Override
    @Transactional
    public ApplicationView submit(Scope scope, SubmitCommand command, String idempotencyKey) {
        requireEnvironment();
        gatewayPolicy.requireSelectable(command.regimeCode(), command.channelCode());
        var profile = profile(scope.tenantNo(), scope.entityNo(), command.regimeCode(), true);
        String keyHash = PrintHashes.sha256(idempotencyKey);
        String payloadHash = PrintHashes.sha256(json.writeValueAsString(command));
        String applicationNo = number("ICA");
        var now = now();
        jdbc.update("""
                INSERT INTO ivc_channel_application
                  (tenant_no,entity_no,application_no,regime_code,channel_code,environment,seller_tax_id,status,
                   current_revision,request_key_hash,request_payload_hash,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'SUBMITTED',1,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, scope.tenantNo(), scope.entityNo(), applicationNo, command.regimeCode(), command.channelCode(),
                environment, profile.getSellerTaxId(), keyHash, payloadHash, scope.actorNo(), now, now);
        var row = jdbc.query("""
                SELECT * FROM ivc_channel_application
                 WHERE tenant_no=? AND entity_no=? AND environment=? AND request_key_hash=?
                 FOR UPDATE
                """, APPLICATION, scope.tenantNo(), scope.entityNo(), environment, keyHash).getFirst();
        if (!row.payloadHash().equals(payloadHash)) throw conflict();
        if (row.view().applicationNo().equals(applicationNo)) {
            addRevision(scope, applicationNo, 1, command.materialRefs(), now);
            event(scope, applicationNo, 1, "SUBMITTED", "SUBMITTED", null, now);
        }
        return row.view();
    }

    @Override
    @Transactional
    public ApplicationView revise(Scope scope, String applicationNo, ReviseCommand command) {
        var app = current(scope, applicationNo, true).view();
        if (!"NEEDS_INFO".equals(app.status())) throw state();
        requireRevision(app, command.expectedRevision());
        int revision = app.currentRevision() + 1;
        var now = now();
        addRevision(scope, applicationNo, revision, command.materialRefs(), now);
        setApplication(scope, applicationNo, revision, "SUBMITTED", null, now);
        event(scope, applicationNo, revision, "RESUBMITTED", "SUBMITTED", null, now);
        return current(scope, applicationNo, true).view();
    }

    @Override
    @Transactional
    public ApplicationView review(Scope scope, String applicationNo, ReviewCommand command) {
        var app = current(scope, applicationNo, true).view();
        requireRevision(app, command.expectedRevision());
        var submitter = jdbc.queryForObject("""
                SELECT submitted_by FROM ivc_channel_application_revision
                 WHERE tenant_no=? AND application_no=? AND revision_no=?
                """, String.class, scope.tenantNo(), applicationNo, app.currentRevision());
        if (scope.actorNo().equals(app.createdBy()) || scope.actorNo().equals(submitter)) throw state();
        if (!"SUBMITTED".equals(app.status())) {
            if (app.status().equals(command.decision())) return app;
            throw state();
        }
        if (!List.of("APPROVED", "NEEDS_INFO", "REJECTED").contains(command.decision())) throw state();
        var now = now();
        setApplication(scope, applicationNo, app.currentRevision(), command.decision(), null, now);
        event(scope, applicationNo, app.currentRevision(), "REVIEWED", command.decision(), command.reason(), now);
        return current(scope, applicationNo, true).view();
    }

    @Override
    @Transactional
    public AccountView verifyAndBind(Scope scope, String applicationNo, int expectedRevision) {
        return verifyAndBind(scope, applicationNo, expectedRevision, null);
    }

    @Override
    @Transactional
    public AccountView verifyAndBind(Scope scope, String applicationNo, int expectedRevision, ChannelCredentials input) {
        var app = current(scope, applicationNo, false).view();
        requireRevision(app, expectedRevision);
        if ("BOUND".equals(app.status())) return account(scope, app.accountNo()).view();
        if (!"APPROVED".equals(app.status())) throw state();

        var staged = profile(scope.tenantNo(), scope.entityNo(), app.regimeCode(), false);
        if (!app.sellerTaxId().equals(staged.getSellerTaxId())) throw state();
        gatewayPolicy.requireSelectable(app.regimeCode(), app.channelCode());
        if (input == null && !app.channelCode().equals(gatewayPolicy.effective(staged).code())) throw state();
        var capability = gateways.capability(app.channelCode(), AccountCapability.class)
                .orElseThrow(() -> BizException.of(ErrorCode.INVOICE_PROVIDER_UNSUPPORTED, "verify_account"));
        var merchant = input != null ? suppliedAccount(app, input) : credentials.of(staged).orElseThrow(
                () -> BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "account_not_configured"));
        AccountCapability.ProviderOffice office;
        try {
            office = capability.verify(merchant);
        } catch (ProviderException ex) {
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, ex.kind().name());
        }
        if (!app.sellerTaxId().equals(office.taxId()))
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_REJECTED, "tax_id_mismatch");

        var fresh = profile(scope.tenantNo(), scope.entityNo(), app.regimeCode(), true);
        if (!Objects.equals(staged.getSellerTaxId(), fresh.getSellerTaxId())) throw conflict();
        if (input == null && (!app.channelCode().equals(gatewayPolicy.effective(fresh).code())
                || !Objects.equals(staged.getProviderAccountCode(), fresh.getProviderAccountCode())
                || !Objects.equals(staged.getProviderSecretRef(), fresh.getProviderSecretRef())
                || !Objects.equals(staged.getQrKeyRef(), fresh.getQrKeyRef()))) throw conflict();
        var locked = current(scope, applicationNo, true).view();
        if ("BOUND".equals(locked.status())) return account(scope, locked.accountNo()).view();
        requireRevision(locked, expectedRevision);
        if (!"APPROVED".equals(locked.status())) throw state();

        String freshNo = number("ICB");
        var now = now();
        jdbc.update("""
                INSERT INTO ivc_channel_account
                  (tenant_no,entity_no,account_no,regime_code,channel_code,environment,seller_tax_id,
                   provider_account_code,current_revision,created_at)
                VALUES (?,?,?,?,?,?,?,?,0,?) ON DUPLICATE KEY UPDATE id=id
                """, scope.tenantNo(), scope.entityNo(), freshNo, app.regimeCode(), app.channelCode(), environment,
                app.sellerTaxId(), merchant.accountCode(), now);
        var identity = jdbc.query("""
                SELECT id, entity_no, account_no, regime_code,seller_tax_id,current_revision FROM ivc_channel_account
                 WHERE tenant_no=? AND channel_code=? AND environment=? AND provider_account_code=? FOR UPDATE
                """, (rs, index) -> new AccountIdentity(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6)), scope.tenantNo(), app.channelCode(),
                environment, merchant.accountCode()).getFirst();
        if (!scope.entityNo().equals(identity.entityNo()) || !app.regimeCode().equals(identity.regimeCode())
                || !app.sellerTaxId().equals(identity.sellerTaxId())) throw conflict();

        Long qrRef = input == null ? staged.getQrKeyRef() : null;
        byte[] qr = TwQrCipher.parseKey(office.qrKey());
        if (qr == null && input != null) qr = TwQrCipher.parseKey(input.qrAesKeyHex());
        if (qr != null) {
            var storage = vault.getIfAvailable();
            if (storage == null) throw BizException.of(ErrorCode.INVOICE_KEY_UNAVAILABLE);
            qrRef = storage.seal(new KeyOwner(SecretNamespace.PLATFORM, "INVOICE_CHANNEL_QR", identity.id()), qr)
                    .blobId();
        }
        long secretRef = input == null ? staged.getProviderSecretRef()
                : credentials.sealChannel(identity.id(), input.user(), input.password());
        long revision = identity.revision() + 1;
        jdbc.update("""
                INSERT INTO ivc_channel_account_revision
                  (tenant_no,account_no,revision_no,application_no,seller_name,provider_secret_ref,qr_key_ref,
                   verified_by,verified_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, scope.tenantNo(), identity.accountNo(), revision, applicationNo, fresh.getSellerName(),
                secretRef, qrRef, scope.actorNo(), now);
        jdbc.update("UPDATE ivc_channel_account SET current_revision=? WHERE id=?", revision, identity.id());
        setApplication(scope, applicationNo, expectedRevision, "BOUND", identity.accountNo(), now);
        event(scope, applicationNo, expectedRevision, "VERIFIED_AND_BOUND", "BOUND", null, now);
        return account(scope, identity.accountNo()).view();
    }

    @Override
    @Transactional
    public RouteView activate(Scope scope, ActivateCommand command) {
        requireEnvironment();
        var snapshot = account(scope, command.accountNo());
        var profile = profile(scope.tenantNo(), scope.entityNo(), snapshot.view().regimeCode(), true);
        if (!profile.getSellerTaxId().equals(snapshot.view().sellerTaxId())) throw state();
        var old = route(scope, profile.getRegimeCode(), true);
        long current = old.map(RouteView::routeVersion).orElse(0L);
        if (current != command.expectedRouteVersion()) {
            if (old.isPresent() && activationReplay(scope, command, old.get())) return old.get();
            throw conflict();
        }
        String currentPeriod = regimes.require(profile.getRegimeCode()).periodOf(Instant.now());
        var range = first(jdbc.query("""
                SELECT channel_account_no, start_no, next_no FROM ivc_number_range
                 WHERE tenant_no=? AND entity_no=? AND regime_code=? AND range_no=?
                   AND period_code=? AND status='ACTIVE' AND next_no <= end_no FOR UPDATE
                """, (rs, i) -> new AccountRange(rs.getString(1), rs.getLong(2), rs.getLong(3)), scope.tenantNo(),
                scope.entityNo(), profile.getRegimeCode(), command.rangeNo(), currentPeriod))
                .orElseThrow(() -> BizException.of(ErrorCode.INVOICE_RANGE_INVALID));
        String other = range.accountNo();
        if ((other == null && range.start() != range.next()) || (other != null && !other.equals(command.accountNo())))
            throw BizException.of(ErrorCode.INVOICE_RANGE_INVALID);
        var now = now();
        if (other == null) {
            jdbc.update("""
                    UPDATE ivc_number_range SET channel_account_no=?, updated_at=?
                     WHERE tenant_no=? AND entity_no=? AND regime_code=? AND range_no=? AND channel_account_no IS NULL
                    """, command.accountNo(), now, scope.tenantNo(), scope.entityNo(),
                    profile.getRegimeCode(), command.rangeNo());
        }
        long next = current + 1;
        if (old.isEmpty()) {
            jdbc.update("""
                    INSERT INTO ivc_channel_route (tenant_no,entity_no,regime_code,environment,active_account_no,
                      route_version,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?)
                    """, scope.tenantNo(), scope.entityNo(), profile.getRegimeCode(), environment,
                    command.accountNo(), next, scope.actorNo(), now);
        } else {
            jdbc.update("""
                    UPDATE ivc_channel_route SET active_account_no=?, route_version=?, updated_by=?, updated_at=?
                     WHERE tenant_no=? AND entity_no=? AND regime_code=? AND environment=?
                    """, command.accountNo(), next, scope.actorNo(), now, scope.tenantNo(), scope.entityNo(),
                    profile.getRegimeCode(), environment);
        }
        jdbc.update("""
                INSERT INTO ivc_channel_route_event (tenant_no,entity_no,regime_code,environment,route_version,
                  previous_account_no,active_account_no,number_range_no,actor_no,reason,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, scope.tenantNo(), scope.entityNo(), profile.getRegimeCode(), environment, next,
                old.map(RouteView::activeAccountNo).orElse(null), command.accountNo(), command.rangeNo(),
                scope.actorNo(), command.reason(), now);
        return route(scope, profile.getRegimeCode(), true).orElseThrow();
    }

    @Override
    public Optional<BoundAccount> activeForIssue(Scope scope, String regimeCode) {
        var profile = profile(scope.tenantNo(), scope.entityNo(), regimeCode, true);
        var route = route(scope, regimeCode, true);
        if (route.isEmpty()) {
            // Once an entity uses managed routing, a missing environment must fail before legacy fallback.
            boolean managed = !jdbc.query("""
                    SELECT id FROM ivc_channel_route WHERE tenant_no=? AND entity_no=? AND regime_code=?
                     LIMIT 1 FOR UPDATE
                    """, (rs, i) -> rs.getLong(1), scope.tenantNo(), scope.entityNo(), regimeCode).isEmpty();
            if (managed) throw state();
        }
        return route.map(row -> {
            var snapshot = account(scope, row.activeAccountNo(), true);
            if (!profile.getSellerTaxId().equals(snapshot.view().sellerTaxId())) throw state();
            var effective = snapshot.profile();
            effective.setMemberCarrierType(profile.getMemberCarrierType());
            return snapshot.bound(row.routeVersion());
        });
    }

    @Override
    public Optional<BoundAccount> historical(String tenantNo, String entityNo, String accountNo) {
        if (environment.isBlank()) return Optional.empty();
        return first(jdbc.query(ACCOUNT_SNAPSHOT + " AND a.account_no=?", ACCOUNT, tenantNo, entityNo,
                environment, accountNo)).map(row -> row.bound(0L));
    }

    @Override
    public ApplicationDetail application(Scope scope, String applicationNo) {
        var app = current(scope, applicationNo, false).view();
        var revisions = jdbc.query("""
                SELECT revision_no,material_refs,submitted_by,submitted_at FROM ivc_channel_application_revision
                 WHERE tenant_no=? AND application_no=? ORDER BY revision_no
                """, (rs, i) -> new RevisionView(rs.getInt(1), json.readValue(rs.getString(2),
                new TypeReference<List<String>>() {}), rs.getString(3), rs.getObject(4, LocalDateTime.class)),
                scope.tenantNo(), applicationNo);
        var events = jdbc.query("""
                SELECT revision_no,action,to_status,actor_no,reason,occurred_at FROM ivc_channel_application_event
                 WHERE tenant_no=? AND application_no=? ORDER BY id
                """, (rs, i) -> new ApplicationEvent(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, LocalDateTime.class)), scope.tenantNo(), applicationNo);
        return new ApplicationDetail(app, revisions, events);
    }

    @Override
    public List<ApplicationView> applications(Scope scope) {
        return jdbc.query("""
                SELECT * FROM ivc_channel_application WHERE tenant_no=? AND entity_no=? AND environment=?
                 ORDER BY id DESC LIMIT 100
                """, APPLICATION, scope.tenantNo(), scope.entityNo(), environment).stream()
                .map(ApplicationRow::view).toList();
    }

    @Override
    public List<AccountView> accounts(Scope scope) {
        return jdbc.query(ACCOUNT_SNAPSHOT + " ORDER BY a.id DESC LIMIT 100", ACCOUNT,
                scope.tenantNo(), scope.entityNo(), environment).stream().map(AccountSnapshot::view).toList();
    }

    @Override
    public List<RouteView> routes(Scope scope) {
        return jdbc.query("""
                SELECT * FROM ivc_channel_route WHERE tenant_no=? AND entity_no=? AND environment=?
                 ORDER BY regime_code
                """, ROUTE, scope.tenantNo(), scope.entityNo(), environment);
    }

    private boolean activationReplay(Scope scope, ActivateCommand command, RouteView route) {
        if (route.routeVersion() != command.expectedRouteVersion() + 1
                || !route.activeAccountNo().equals(command.accountNo())) return false;
        return !jdbc.query("""
                SELECT id FROM ivc_channel_route_event
                 WHERE tenant_no=? AND entity_no=? AND regime_code=? AND environment=? AND route_version=?
                   AND number_range_no=? AND actor_no=? AND reason=?
                """, (rs, i) -> rs.getLong(1), scope.tenantNo(), scope.entityNo(), route.regimeCode(), environment,
                route.routeVersion(), command.rangeNo(), scope.actorNo(), command.reason()).isEmpty();
    }

    private IvcRegimeProfile profile(String tenant, String entity, String regime, boolean locked) {
        var query = new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", tenant).eq("entity_no", entity)
                .eq("regime_code", regime).eq("status", "ACTIVE");
        if (locked) query.last("FOR UPDATE");
        var profile = profiles.selectOne(query);
        if (profile == null) throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);
        return profile;
    }

    private Optional<RouteView> route(Scope scope, String regime, boolean locked) {
        return first(jdbc.query("""
                SELECT * FROM ivc_channel_route WHERE tenant_no=? AND entity_no=? AND regime_code=? AND environment=?
                """ + (locked ? " FOR UPDATE" : ""), ROUTE, scope.tenantNo(), scope.entityNo(), regime, environment));
    }

    private AccountSnapshot account(Scope scope, String no) {
        return account(scope, no, false);
    }

    private AccountSnapshot account(Scope scope, String no, boolean locked) {
        String query = ACCOUNT_SNAPSHOT + " AND a.account_no=?" + (locked ? " FOR UPDATE" : "");
        return first(jdbc.query(query, ACCOUNT, scope.tenantNo(), scope.entityNo(), environment, no))
                .orElseThrow(() -> BizException.of(ErrorCode.INVOICE_NOT_FOUND));
    }

    private ProviderAccount suppliedAccount(ApplicationView app, ChannelCredentials input) {
        var field = gateways.find(app.channelCode()).orElseThrow().profile()
                .credential(CredentialField.Slot.ACCOUNT_CODE);
        String code = input.accountCode();
        if (field != null && field.derivedFrom() == CredentialField.Derived.SELLER_TAX_ID) {
            if (code != null && !app.sellerTaxId().equals(code))
                throw BizException.of(ErrorCode.INVOICE_PROVIDER_ACCOUNT_INVALID, field.labelKey());
            code = app.sellerTaxId();
        }
        if (code == null || code.isBlank()
                || (field != null && field.pattern() != null && !code.matches(field.pattern())))
            throw BizException.of(ErrorCode.INVOICE_PROVIDER_ACCOUNT_INVALID, "accountCode");
        return new ProviderAccount(code, input.user(), input.password());
    }

    private ApplicationRow current(Scope scope, String no, boolean locked) {
        return first(jdbc.query("""
                SELECT * FROM ivc_channel_application
                 WHERE tenant_no=? AND entity_no=? AND environment=? AND application_no=?
                """ + (locked ? " FOR UPDATE" : ""), APPLICATION, scope.tenantNo(), scope.entityNo(), environment,
                no)).orElseThrow(() -> BizException.of(ErrorCode.INVOICE_NOT_FOUND));
    }

    private void addRevision(Scope scope, String no, int revision, List<String> refs, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO ivc_channel_application_revision
                  (tenant_no,application_no,revision_no,material_refs,submitted_by,submitted_at)
                  VALUES (?,?,?,?,?,?)
                """, scope.tenantNo(), no, revision, json.writeValueAsString(refs), scope.actorNo(), now);
    }

    private void setApplication(Scope scope, String no, int revision, String status, String account, LocalDateTime now) {
        jdbc.update("""
                UPDATE ivc_channel_application SET status=?,current_revision=?,account_no=?,updated_at=?
                 WHERE tenant_no=? AND entity_no=? AND environment=? AND application_no=?
                """, status, revision, account, now, scope.tenantNo(), scope.entityNo(), environment, no);
    }

    private void event(Scope scope, String no, int revision, String action, String state, String reason, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO ivc_channel_application_event
                  (tenant_no,application_no,revision_no,action,to_status,actor_no,reason,occurred_at)
                  VALUES (?,?,?,?,?,?,?,?)
                """, scope.tenantNo(), no, revision, action, state, scope.actorNo(), reason, now);
    }

    private void requireEnvironment() {
        if (!"TEST".equals(environment) && !"PRODUCTION".equals(environment)) throw state();
    }

    private static void requireRevision(ApplicationView app, int revision) {
        if (app.currentRevision() != revision) throw conflict();
    }

    private static BizException state() { return BizException.of(ErrorCode.INVOICE_STATE_INVALID); }

    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }

    private static String number(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private record ApplicationRow(ApplicationView view, String payloadHash) {}

    private record AccountIdentity(long id, String entityNo, String accountNo, String regimeCode, String sellerTaxId,
            long revision) {}

    private record AccountRange(String accountNo, long start, long next) {}

    private record AccountSnapshot(AccountView view, IvcRegimeProfile profile) {
        BoundAccount bound(long routeVersion) {
            return new BoundAccount(view.accountNo(), view.channelCode(), view.environment(), view.currentRevision(),
                    routeVersion, profile);
        }
    }

    private static final RowMapper<ApplicationRow> APPLICATION = (rs, i) -> new ApplicationRow(new ApplicationView(
            rs.getString("application_no"), rs.getString("entity_no"), rs.getString("regime_code"),
            rs.getString("channel_code"), rs.getString("environment"), rs.getString("seller_tax_id"),
            rs.getString("status"), rs.getInt("current_revision"), rs.getString("account_no"),
            rs.getString("created_by"), time(rs, "updated_at")), rs.getString("request_payload_hash"));

    private static final RowMapper<RouteView> ROUTE = (rs, i) -> new RouteView(rs.getString("entity_no"),
            rs.getString("regime_code"), rs.getString("environment"), rs.getString("active_account_no"),
            rs.getLong("route_version"), time(rs, "updated_at"));

    private static final RowMapper<AccountSnapshot> ACCOUNT = (rs, i) -> {
        var view = new AccountView(rs.getString("account_no"), rs.getString("entity_no"), rs.getString("regime_code"),
                rs.getString("channel_code"), rs.getString("environment"), rs.getString("seller_tax_id"),
                rs.getLong("current_revision"), time(rs, "verified_at"));
        var profile = new IvcRegimeProfile();
        profile.setTenantNo(rs.getString("tenant_no"));
        profile.setEntityNo(view.entityNo());
        profile.setRegimeCode(view.regimeCode());
        profile.setGatewayCode(view.channelCode());
        profile.setSellerTaxId(view.sellerTaxId());
        profile.setSellerName(rs.getString("seller_name"));
        profile.setProviderAccountCode(rs.getString("provider_account_code"));
        profile.setProviderSecretRef(rs.getObject("provider_secret_ref", Long.class));
        profile.setQrKeyRef(rs.getObject("qr_key_ref", Long.class));
        profile.setStatus("ACTIVE");
        return new AccountSnapshot(view, profile);
    };

    private static LocalDateTime time(ResultSet rs, String key) throws SQLException {
        return rs.getObject(key, LocalDateTime.class);
    }
}
