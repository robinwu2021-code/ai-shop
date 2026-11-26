package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.config.ModuleDataSources;
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
import ai.neargo.shop.invoice.legacy.InvoiceHistory;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.RegimeProfileMapper;
import ai.neargo.shop.invoice.regime.InvoiceRegimes;
import ai.neargo.shop.invoice.regime.tw.TwQrCipher;
import ai.neargo.shop.invoice.service.InvoiceChannelService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.print.api.PrintHashes;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 开票通道：开通申请、通道账户、当前路由。
 *
 * <p>数据分在两个库（TDD-多数据源与发票打印对齐新库 §2.4）：
 * <ul>
 *   <li>发票库：通道账户 {@code ivc_channel_account}（只留当前版）、路由（并在 {@code ivc_regime_profile} 上）、号段；</li>
 *   <li>主库：开通申请三张表、账户历史版、路由事件 —— 审批与历史记录，等审核服务与统一审计接上后搬走。</li>
 * </ul>
 * 两边没有共同事务。只碰申请的流程用主库事务；开票路径只碰发票库；
 * 跨两边的只有「核验并绑定」，分三段做，见 {@link #verifyAndBind(Scope, String, int, ChannelCredentials)}。
 */
@Service
public class InvoiceChannelServiceImpl implements InvoiceChannelService {

    /** 绑定过的账户（版本号从 1 起）；占位行的版本号是 0，不对外可见。 */
    private static final String ACCOUNT_SNAPSHOT = """
            SELECT * FROM ivc_channel_account
             WHERE tenant_no=? AND entity_no=? AND environment=? AND current_revision>0
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate invoiceTx;
    private final InvoiceHistory history;
    private final RegimeProfileMapper profiles;
    private final InvoiceGatewayPolicy gatewayPolicy;
    private final InvoiceGateways gateways;
    private final InvoiceRegimes regimes;
    private final InvoiceProviderAccounts credentials;
    private final ObjectProvider<SecretVault> vault;
    private final ObjectMapper json;
    private final String environment;

    public InvoiceChannelServiceImpl(@Qualifier(ModuleDataSources.INVOICE_DATA_SOURCE) DataSource dataSource,
            @Qualifier(ModuleDataSources.INVOICE_TX_MANAGER) PlatformTransactionManager invoiceTransactions,
            InvoiceHistory history, RegimeProfileMapper profiles, InvoiceGatewayPolicy gatewayPolicy,
            InvoiceGateways gateways, InvoiceRegimes regimes, InvoiceProviderAccounts credentials,
            ObjectProvider<SecretVault> vault, ObjectMapper json,
            @Value("${shop.invoice.channel.environment:}") String environment) {
        // 账户、路由是手写 SQL，必须和发票的 Mapper 落在同一个库、同一个事务里
        this.jdbc = new JdbcTemplate(dataSource);
        this.invoiceTx = new TransactionTemplate(invoiceTransactions);
        this.history = history;
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
    public ApplicationView submit(Scope scope, SubmitCommand command, String idempotencyKey) {
        requireEnvironment();
        gatewayPolicy.requireSelectable(command.regimeCode(), command.channelCode());
        var profile = profile(scope.tenantNo(), scope.entityNo(), command.regimeCode(), false);
        String keyHash = PrintHashes.sha256(idempotencyKey);
        String payloadHash = PrintHashes.sha256(json.writeValueAsString(command));
        String applicationNo = number("ICA");
        var now = now();
        return history.primary().execute(tx -> {
            legacy().update("""
                    INSERT INTO ivc_channel_application
                      (tenant_no,entity_no,application_no,regime_code,channel_code,environment,seller_tax_id,status,
                       current_revision,request_key_hash,request_payload_hash,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,'SUBMITTED',1,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE id=id
                    """, scope.tenantNo(), scope.entityNo(), applicationNo, command.regimeCode(),
                    command.channelCode(), environment, profile.getSellerTaxId(), keyHash, payloadHash,
                    scope.actorNo(), now, now);
            var row = legacy().query("""
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
        });
    }

    @Override
    public ApplicationView revise(Scope scope, String applicationNo, ReviseCommand command) {
        return history.primary().execute(tx -> {
            var app = current(scope, applicationNo, true).view();
            if (!"NEEDS_INFO".equals(app.status())) throw state();
            requireRevision(app, command.expectedRevision());
            int revision = app.currentRevision() + 1;
            var now = now();
            addRevision(scope, applicationNo, revision, command.materialRefs(), now);
            setApplication(scope, applicationNo, revision, "SUBMITTED", null, now);
            event(scope, applicationNo, revision, "RESUBMITTED", "SUBMITTED", null, now);
            return current(scope, applicationNo, true).view();
        });
    }

    @Override
    public ApplicationView review(Scope scope, String applicationNo, ReviewCommand command) {
        return history.primary().execute(tx -> {
            var app = current(scope, applicationNo, true).view();
            requireRevision(app, command.expectedRevision());
            var submitter = legacy().queryForObject("""
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
        });
    }

    @Override
    public AccountView verifyAndBind(Scope scope, String applicationNo, int expectedRevision) {
        return verifyAndBind(scope, applicationNo, expectedRevision, null);
    }

    /**
     * 核验并绑定，分三段，每段各自提交：
     * <ol>
     *   <li>读申请（主库），向服务商核验；</li>
     *   <li>发票库事务：建或取账户、换上这次核验过的凭据，版本号加一；</li>
     *   <li>主库事务：锁申请复核状态，记账户历史版，申请置为已绑定。</li>
     * </ol>
     * 第二段成功、第三段失败时，账户已是新凭据而申请仍是「已通过」：重做一次即可，
     * 账户按服务商账号唯一，重做只会再加一版，不会多出账户。
     */
    @Override
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

        var now = now();
        var bound = invoiceTx.execute(tx -> bindAccount(scope, app, staged, merchant, office, input, now));
        return history.primary().execute(tx -> {
            var locked = current(scope, applicationNo, true).view();
            if ("BOUND".equals(locked.status())) return account(scope, locked.accountNo()).view();
            requireRevision(locked, expectedRevision);
            if (!"APPROVED".equals(locked.status())) throw state();
            legacy().update("""
                    INSERT INTO ivc_channel_account_revision
                      (tenant_no,account_no,revision_no,application_no,seller_name,provider_secret_ref,qr_key_ref,
                       verified_by,verified_at) VALUES (?,?,?,?,?,?,?,?,?)
                    """, scope.tenantNo(), bound.accountNo(), bound.revision(), applicationNo, bound.sellerName(),
                    bound.secretRef(), bound.qrRef(), scope.actorNo(), now);
            setApplication(scope, applicationNo, expectedRevision, "BOUND", bound.accountNo(), now);
            event(scope, applicationNo, expectedRevision, "VERIFIED_AND_BOUND", "BOUND", null, now);
            return account(scope, bound.accountNo()).view();
        });
    }

    private BoundRevision bindAccount(Scope scope, ApplicationView app, IvcRegimeProfile staged, ProviderAccount merchant,
            AccountCapability.ProviderOffice office, ChannelCredentials input, LocalDateTime now) {
        var fresh = profile(scope.tenantNo(), scope.entityNo(), app.regimeCode(), true);
        if (!Objects.equals(staged.getSellerTaxId(), fresh.getSellerTaxId())) throw conflict();
        if (input == null && (!app.channelCode().equals(gatewayPolicy.effective(fresh).code())
                || !Objects.equals(staged.getProviderAccountCode(), fresh.getProviderAccountCode())
                || !Objects.equals(staged.getProviderSecretRef(), fresh.getProviderSecretRef())
                || !Objects.equals(staged.getQrKeyRef(), fresh.getQrKeyRef()))) throw conflict();

        String freshNo = number("ICB");
        jdbc.update("""
                INSERT INTO ivc_channel_account
                  (tenant_no,entity_no,account_no,regime_code,channel_code,environment,seller_tax_id,
                   provider_account_code,current_revision,status,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,0,'ACTIVE',?,?) ON DUPLICATE KEY UPDATE id=id
                """, scope.tenantNo(), scope.entityNo(), freshNo, app.regimeCode(), app.channelCode(), environment,
                app.sellerTaxId(), merchant.accountCode(), scope.actorNo(), now);
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
                UPDATE ivc_channel_account
                   SET current_revision=?, sec_key_id=?, qr_sec_key_id=?, review_no=?, updated_by=?, updated_at=?
                 WHERE id=?
                """, revision, String.valueOf(secretRef), qrRef == null ? null : String.valueOf(qrRef),
                app.applicationNo(), scope.actorNo(), now, identity.id());
        return new BoundRevision(identity.accountNo(), revision, fresh.getSellerName(), secretRef, qrRef);
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.INVOICE_TX_MANAGER)
    public RouteView activate(Scope scope, ActivateCommand command) {
        requireEnvironment();
        var snapshot = account(scope, command.accountNo());
        var profile = profile(scope.tenantNo(), scope.entityNo(), snapshot.view().regimeCode(), true);
        if (!profile.getSellerTaxId().equals(snapshot.view().sellerTaxId())) throw state();
        var old = route(profile);
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
        profiles.update(null, new UpdateWrapper<IvcRegimeProfile>().eq("id", profile.getId())
                .set("active_account_no", command.accountNo()).set("route_version", next)
                .set("environment", environment).set("updated_by", scope.actorNo()).set("updated_at", now));
        String previous = old.map(RouteView::activeAccountNo).orElse(null);
        // 路由事件是历史记录，在主库：路由切换提交之后再记
        history.afterCommit("channel.route.activated", () -> legacy().update("""
                INSERT INTO ivc_channel_route_event (tenant_no,entity_no,regime_code,environment,route_version,
                  previous_account_no,active_account_no,number_range_no,actor_no,reason,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, scope.tenantNo(), scope.entityNo(), profile.getRegimeCode(), environment, next,
                previous, command.accountNo(), command.rangeNo(), scope.actorNo(), command.reason(), now));
        return new RouteView(scope.entityNo(), profile.getRegimeCode(), environment, command.accountNo(), next, now);
    }

    @Override
    public Optional<BoundAccount> activeForIssue(Scope scope, String regimeCode) {
        var profile = profile(scope.tenantNo(), scope.entityNo(), regimeCode, true);
        var route = route(profile);
        // 主体一旦用了托管路由，本环境没有路由就必须失败，不能退回主体级的旧配置
        if (route.isEmpty() && profile.getActiveAccountNo() != null) throw state();
        return route.map(row -> {
            var snapshot = account(scope, row.activeAccountNo(), true);
            if (!profile.getSellerTaxId().equals(snapshot.view().sellerTaxId())) throw state();
            var effective = snapshot.profile();
            effective.setSellerName(profile.getSellerName());
            effective.setMemberCarrierType(profile.getMemberCarrierType());
            return snapshot.bound(row.routeVersion());
        });
    }

    @Override
    public Optional<BoundAccount> historical(String tenantNo, String entityNo, String accountNo) {
        if (environment.isBlank()) return Optional.empty();
        return first(jdbc.query(ACCOUNT_SNAPSHOT + " AND account_no=?", ACCOUNT, tenantNo, entityNo,
                environment, accountNo)).map(row -> {
                    var owner = profiles.selectOne(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", tenantNo)
                            .eq("entity_no", entityNo).eq("regime_code", row.view().regimeCode()));
                    if (owner != null) row.profile().setSellerName(owner.getSellerName());
                    return row.bound(0L);
                });
    }

    @Override
    public ApplicationDetail application(Scope scope, String applicationNo) {
        var app = current(scope, applicationNo, false).view();
        var revisions = legacy().query("""
                SELECT revision_no,material_refs,submitted_by,submitted_at FROM ivc_channel_application_revision
                 WHERE tenant_no=? AND application_no=? ORDER BY revision_no
                """, (rs, i) -> new RevisionView(rs.getInt(1), json.readValue(rs.getString(2),
                new TypeReference<List<String>>() {}), rs.getString(3), rs.getObject(4, LocalDateTime.class)),
                scope.tenantNo(), applicationNo);
        var events = legacy().query("""
                SELECT revision_no,action,to_status,actor_no,reason,occurred_at FROM ivc_channel_application_event
                 WHERE tenant_no=? AND application_no=? ORDER BY id
                """, (rs, i) -> new ApplicationEvent(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, LocalDateTime.class)), scope.tenantNo(), applicationNo);
        return new ApplicationDetail(app, revisions, events);
    }

    @Override
    public List<ApplicationView> applications(Scope scope) {
        return legacy().query("""
                SELECT * FROM ivc_channel_application WHERE tenant_no=? AND entity_no=? AND environment=?
                 ORDER BY id DESC LIMIT 100
                """, APPLICATION, scope.tenantNo(), scope.entityNo(), environment).stream()
                .map(ApplicationRow::view).toList();
    }

    @Override
    public List<AccountView> accounts(Scope scope) {
        return jdbc.query(ACCOUNT_SNAPSHOT + " ORDER BY id DESC LIMIT 100", ACCOUNT,
                scope.tenantNo(), scope.entityNo(), environment).stream().map(AccountSnapshot::view).toList();
    }

    @Override
    public List<RouteView> routes(Scope scope) {
        return profiles.selectList(new QueryWrapper<IvcRegimeProfile>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("environment", environment).isNotNull("active_account_no")
                .orderByAsc("regime_code")).stream().map(this::route).flatMap(Optional::stream).toList();
    }

    private boolean activationReplay(Scope scope, ActivateCommand command, RouteView route) {
        if (route.routeVersion() != command.expectedRouteVersion() + 1
                || !route.activeAccountNo().equals(command.accountNo())) return false;
        return !legacy().query("""
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

    /** 路由并在税制行上；只有路由的环境就是本进程的环境时才算有路由。 */
    private Optional<RouteView> route(IvcRegimeProfile profile) {
        if (profile.getActiveAccountNo() == null || !environment.equals(profile.getEnvironment())) {
            return Optional.empty();
        }
        return Optional.of(new RouteView(profile.getEntityNo(), profile.getRegimeCode(), profile.getEnvironment(),
                profile.getActiveAccountNo(), profile.getRouteVersion(), profile.getUpdatedAt()));
    }

    private AccountSnapshot account(Scope scope, String no) {
        return account(scope, no, false);
    }

    private AccountSnapshot account(Scope scope, String no, boolean locked) {
        String query = ACCOUNT_SNAPSHOT + " AND account_no=?" + (locked ? " FOR UPDATE" : "");
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

    private JdbcTemplate legacy() {
        return history.jdbc();
    }

    private ApplicationRow current(Scope scope, String no, boolean locked) {
        return first(legacy().query("""
                SELECT * FROM ivc_channel_application
                 WHERE tenant_no=? AND entity_no=? AND environment=? AND application_no=?
                """ + (locked ? " FOR UPDATE" : ""), APPLICATION, scope.tenantNo(), scope.entityNo(), environment,
                no)).orElseThrow(() -> BizException.of(ErrorCode.INVOICE_NOT_FOUND));
    }

    private void addRevision(Scope scope, String no, int revision, List<String> refs, LocalDateTime now) {
        legacy().update("""
                INSERT INTO ivc_channel_application_revision
                  (tenant_no,application_no,revision_no,material_refs,submitted_by,submitted_at)
                  VALUES (?,?,?,?,?,?)
                """, scope.tenantNo(), no, revision, json.writeValueAsString(refs), scope.actorNo(), now);
    }

    private void setApplication(Scope scope, String no, int revision, String status, String account, LocalDateTime now) {
        legacy().update("""
                UPDATE ivc_channel_application SET status=?,current_revision=?,account_no=?,updated_at=?
                 WHERE tenant_no=? AND entity_no=? AND environment=? AND application_no=?
                """, status, revision, account, now, scope.tenantNo(), scope.entityNo(), environment, no);
    }

    private void event(Scope scope, String no, int revision, String action, String state, String reason, LocalDateTime now) {
        legacy().update("""
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

    /** 编号列是 32 位：三位前缀加 29 位随机十六进制。 */
    private static String number(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private record ApplicationRow(ApplicationView view, String payloadHash) {}

    private record AccountIdentity(long id, String entityNo, String accountNo, String regimeCode, String sellerTaxId,
            long revision) {}

    private record BoundRevision(String accountNo, long revision, String sellerName, long secretRef, Long qrRef) {}

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

    /** 账户只留当前版：核验时间就是账户最近一次更新的时间；卖方名称由调用方从税制行补上。 */
    private static final RowMapper<AccountSnapshot> ACCOUNT = (rs, i) -> {
        var view = new AccountView(rs.getString("account_no"), rs.getString("entity_no"), rs.getString("regime_code"),
                rs.getString("channel_code"), rs.getString("environment"), rs.getString("seller_tax_id"),
                rs.getLong("current_revision"), time(rs, "updated_at"));
        var profile = new IvcRegimeProfile();
        profile.setTenantNo(rs.getString("tenant_no"));
        profile.setEntityNo(view.entityNo());
        profile.setRegimeCode(view.regimeCode());
        profile.setGatewayCode(view.channelCode());
        profile.setSellerTaxId(view.sellerTaxId());
        profile.setProviderAccountCode(rs.getString("provider_account_code"));
        profile.setProviderSecretRef(reference(rs.getString("sec_key_id")));
        profile.setQrKeyRef(reference(rs.getString("qr_sec_key_id")));
        profile.setStatus("ACTIVE");
        return new AccountSnapshot(view, profile);
    };

    private static Long reference(String key) {
        return key == null ? null : Long.valueOf(key);
    }

    private static LocalDateTime time(ResultSet rs, String key) throws SQLException {
        return rs.getObject(key, LocalDateTime.class);
    }
}
