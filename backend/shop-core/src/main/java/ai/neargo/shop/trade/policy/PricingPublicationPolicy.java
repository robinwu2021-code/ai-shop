package ai.neargo.shop.trade.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.Function;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.CompilationResult;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingRuleCatalog;
import ai.neargo.shop.common.pricing.PricingConfigCompiler;
import ai.neargo.shop.common.pricing.SystemConstraints;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.PricingConfigPublished;
import ai.neargo.shop.trade.dto.PricingConfigCommands;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.UpgradeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.CreateTemplateReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.InitializeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.entity.OrdPricingTemplate;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateRevisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingRevisionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

/** Same-store explicit SQL predicates and a short transaction own all publication writes, including receipts/events. */
@Component
public class PricingPublicationPolicy {

    private final PricingTemplateMapper templates;
    private final PricingTemplateRevisionMapper templateRevisions;
    private final StorePricingConfigMapper stores;
    private final StorePricingRevisionMapper storeRevisions;
    private final IdempotencyService idempotency;
    private final OutboxEventBus events;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final PricingConfigCompiler compiler = new PricingConfigCompiler();
    private final JsonMapper strictJson = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public PricingPublicationPolicy(PricingTemplateMapper templates, PricingTemplateRevisionMapper templateRevisions,
            StorePricingConfigMapper stores, StorePricingRevisionMapper storeRevisions, IdempotencyService idempotency,
            OutboxEventBus events, @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        this.templates = templates;
        this.templateRevisions = templateRevisions;
        this.stores = stores;
        this.storeRevisions = storeRevisions;
        this.idempotency = idempotency;
        this.events = events;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    /** A template draft is not active until an explicit preview and publication. */
    public DraftVO create(Context c, CreateTemplateReq req, String key) {
        return authorized(c, true, () -> {
            PricingConfigCommands.identity(req.templateCode());
            if (!Set.of("FOOD", "RETAIL", "BEAUTY_SERVICE").contains(req.industryCode())) {
                throw bad();
            }
            if (req.name() == null || req.name().isBlank() || req.name().length() > 128) throw bad();
            var normalized = new CreateTemplateReq(req.industryCode(), req.templateCode(), req.name(),
                    content(req.definition()));
            return write(c, "CREATE", req.industryCode() + ":" + req.templateCode(), true,
                    normalized, key, DraftVO.class, () -> {
                        var row = new OrdPricingTemplate();
                        audit(row, c);
                        row.setTemplateNo(BizKey.next("PT"));
                        row.setIndustryCode(req.industryCode());
                        row.setTemplateCode(req.templateCode());
                        row.setName(req.name());
                        row.setStatus("ACTIVE");
                        row.setDraftVersion(0L);
                        row.setDraftSchemaVersion(draftSchemaVersion(normalized.definition()));
                        row.setDraftContent(normalized.definition());
                        templates.insert(row);
                        return head(c, row.getTemplateNo(), true, false).draft();
                    });
        });
    }

    /** Initialization atomically publishes the explicit initial override pinned to one immutable template revision. */
    public RevisionVO initialize(Context c, InitializeReq req, String key) {
        var normalized = normalize(req);
        return authorized(c, false, () -> {
            reason(normalized.reason());
            String endpoint = endpoint(c, "INIT", normalized.templateRevisionNo(), false);
            String bodyHash = digest(canonical(normalized));
            var completed = idempotency.findCompleted(key(key), endpoint, c.actorNo(), bodyHash, RevisionVO.class);
            if (completed.isPresent()) return completed.get();
            var template = templateRevision(c, normalized.templateRevisionNo());
            var compilation = compile(c, template.getDefinitionSnapshot(), normalized.initialOverride());
            requireValid(compilation);
            return write(c, "INIT", normalized.templateRevisionNo(), false, normalized, key, RevisionVO.class, () -> {
                fresh(c);
                var row = new OrdStorePricingConfig();
                audit(row, c);
                row.setConfigNo(BizKey.next("PC"));
                row.setEntityNo(c.entityNo());
                row.setStoreNo(c.storeNo());
                row.setIndustryCode(template.getIndustryCode());
                row.setTemplateNo(template.getTemplateNo());
                row.setStatus("ACTIVE");
                row.setDraftVersion(0L);
                row.setDraftSchemaVersion(compilation.config().definition().schemaVersion());
                row.setDraftOverride(normalized.initialOverride());
                row.setDraftTemplateRevisionNo(template.getRevisionNo());
                stores.insert(row);
                var current = head(c, row.getConfigNo(), false, true);
                return append(c, current, compilation, template.getRevisionNo(), normalized.initialOverride(), normalized.reason(), null, false);
            });
        });
    }

    public DraftVO getDraft(Context c, String no, boolean template) {
        return authorized(c, template, () -> head(c, no, template, false).draft());
    }

    /** Store template upgrades are explicit draft changes;
    this method never moves an active pointer. */
    public DraftVO saveDraft(Context c, String no, DraftSaveReq req, String key, boolean template) {
        return authorized(c, template, () -> {
            var normalized = new DraftSaveReq(req.expectedDraftVersion(), content(req.content()),
                    req.templateRevisionNo());
            return write(c, "SAVE", no, template, normalized, key, DraftVO.class, () -> {
                var current = head(c, no, template, true);
                expected(current, req.expectedDraftVersion(), current.draft().activeRevisionNo());
                if (!template) {
                    var locked = templateRevision(c, req.templateRevisionNo());
                    if (!locked.getTemplateNo().equals(current.templateNo())) throw bad();
                } else if (req.templateRevisionNo() != null) throw bad();
                change(c, current, normalized.content(), normalized.templateRevisionNo(),
                        current.draft().activeRevisionNo(), template);
                return head(c, no, template, false).draft();
            });
        });
    }

    /** Reset deletes explicit override leaves; false and null never mean inheritance. */
    public DraftVO resetOverrides(Context c, String no, ResetReq req, String key) {
        return authorized(c, false, () -> write(c, "RESET", no, false, req, key, DraftVO.class, () -> {
            var head = head(c, no, false, true);
            expected(head, req.expectedDraftVersion(), head.draft().activeRevisionNo());
            var template = templateRevision(c, head.draft().templateRevisionNo());
            // Draft-only syntax/path validation is not an owner capability grant or a publication.
            var validation = compiler.compile(template.getDefinitionSnapshot(), head.draft().content(),
                    Set.copyOf(req.paths()), SystemConstraints.standard(),
                    new PricingCapabilities(8, 4, true, true, true));
            requireValid(validation);
            var patch = (ObjectNode) json.readTree(head.draft().content());
            for (String path : req.paths()) {
                String[] segments = path.split("\\.");
                JsonNode parent = patch;
                for (int i = 0; i < segments.length - 1; i++) parent = parent.path(segments[i]);
                if (parent.isObject()) ((ObjectNode) parent).remove(segments[segments.length - 1]);
            }
            change(c, head, canonical(patch), head.draft().templateRevisionNo(),
                    head.draft().activeRevisionNo(), false);
            return head(c, no, false, false).draft();
        }));
    }

    /** An upgrade pins a selected published template in the draft; it never activates it implicitly. */
    public DraftVO upgradeTemplate(Context c, String no, UpgradeReq req, String key) {
        return authorized(c, false, () -> write(c, "UPGRADE", no, false, req, key, DraftVO.class, () -> {
            var head = head(c, no, false, true);
            expected(head, req.expectedDraftVersion(), head.draft().activeRevisionNo());
            var template = templateRevision(c, req.templateRevisionNo());
            if (!template.getTemplateNo().equals(head.templateNo())) throw bad();
            change(c, head, head.draft().content(), template.getRevisionNo(), head.draft().activeRevisionNo(), false);
            return head(c, no, false, false).draft();
        }));
    }

    /** API callers retain receipt recovery even if a live owner has become unavailable since success. */
    public RevisionVO publish(Context identity, String no, PublishReq req, String key, boolean template,
            Supplier<Context> evidence) {
        return resolveForCommand(identity, "PUBLISH", no, req, key, template, evidence,
                live -> publish(live, no, req, key, template));
    }

    public RevisionVO initialize(Context identity, InitializeReq req, String key, Supplier<Context> evidence) {
        var normalized = normalize(req);
        return resolveForCommand(identity, "INIT", normalized.templateRevisionNo(), normalized, key, false, evidence,
                live -> initialize(live, normalized, key));
    }

    private RevisionVO resolveForCommand(Context identity, String op, String no, Object req, String key,
            boolean template, Supplier<Context> evidence, Function<Context, RevisionVO> action) {
        var completed = authorized(identity, template, () -> idempotency.findCompleted(key(key),
                endpoint(identity, op, no, template), identity.actorNo(),
                    digest(canonical(req)), RevisionVO.class));
        if (completed.isPresent()) return completed.get();
        try {
            // Do not propagate the config SQL data-scope bypass into an external evidence owner.
            Context live = evidence.get();
            if (live == null || !Objects.equals(identity.tenantNo(), live.tenantNo())
                    || !Objects.equals(identity.actorNo(), live.actorNo())
                    || !Objects.equals(identity.entityNo(), live.entityNo())
                    || !Objects.equals(identity.storeNo(), live.storeNo())
                    || identity.platformOperator() != live.platformOperator()) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return action.apply(live);
        } catch (BizException blocked) {
            var raced = authorized(identity, template, () -> idempotency.findCompleted(key,
                    endpoint(identity, op, no, template), identity.actorNo(),
                    digest(canonical(req)), RevisionVO.class));
            if (raced.isPresent()) return raced.get();
            throw blocked;
        }
    }

    public PreviewVO preview(Context c, String no, long version, String rollbackFrom, boolean template) {
        return authorized(c, template, () -> prepare(c, head(c, no, template, false), version, rollbackFrom,
                template).preview());
    }

    public RevisionVO publish(Context c, String no, PublishReq req, String key, boolean template) {
        return authorized(c, template, () -> {
            reason(req.reason());
            var complete = idempotency.findCompleted(key(key), endpoint(c, "PUBLISH", no, template),
                    c.actorNo(), digest(canonical(req)), RevisionVO.class);
            if (complete.isPresent()) return complete.get();
            try {
                var current = head(c, no, template, false);
                expected(current, req.expectedDraftVersion(), req.expectedActiveRevisionNo());
                var prepared = prepare(c, current, req.expectedDraftVersion(), req.rollbackFromRevisionNo(), template);
                requireValid(prepared.preview().compilation());
                if (!prepared.preview().previewHash().equals(req.previewHash())) throw conflict();
                return write(c, "PUBLISH", no, template, req, key, RevisionVO.class, () -> {
                    var locked = head(c, no, template, true);
                    expected(locked, req.expectedDraftVersion(), req.expectedActiveRevisionNo());
                    fresh(c);
                    return append(c, locked, prepared.preview().compilation(), prepared.templateRevisionNo(),
                            prepared.override(), req.reason(), req.rollbackFromRevisionNo(), template);
                });
            } catch (BizException changedWhilePreparing) {
                // A same-key first writer may have committed between our receipt miss and head read.
                var raced = idempotency.findCompleted(key, endpoint(c, "PUBLISH", no, template),
                        c.actorNo(), digest(canonical(req)), RevisionVO.class);
                if (raced.isPresent()) return raced.get();
                throw changedWhilePreparing;
            }
        });
    }

    public RevisionVO getRevision(Context c, String no, String revisionNo, boolean template) {
        return authorized(c, template, () -> {
            head(c, no, template, false);
            return revision(c, no, revisionNo, template);
        });
    }

    private Prepared prepare(Context c, Head head, long version, String rollback, boolean template) {
        expected(head, version, head.draft().activeRevisionNo());
        String patch = template ? "{}" : head.draft().content();
        String templateNo = head.draft().templateRevisionNo();
        String definition = head.draft().content();
        if (rollback != null) {
            if (template) throw bad();
            var old = revision(c, head.draft().no(), rollback, false);
            patch = old.overrideSnapshot();
            templateNo = old.templateRevisionNo();
        }
        if (!template) {
            var locked = templateRevision(c, templateNo);
            if (!head.templateNo().equals(locked.getTemplateNo())) throw bad();
            definition = locked.getDefinitionSnapshot();
        }
        var compilation = compile(c, definition, patch);
        String config = compilation.valid() ? compilation.config().configHash() : canonical(compilation.issues());
        var evidence = Arrays.asList(c.tenantNo(), c.actorNo(), c.platformOperator(), c.entityNo(), c.storeNo(),
                c.capabilities(), c.capabilityRef(), c.expiresAt(), c.verifiedTaxProfiles().stream().sorted().toList());
        String hash = digest(canonical(Arrays.asList(evidence, head.draft(), templateNo, patch, rollback, config)));
        return new Prepared(new PreviewVO(hash, compilation), templateNo, patch);
    }

    private CompilationResult compile(Context c, String definition, String patch) {
        fresh(c);
        var result = c.platformOperator()
                ? compiler.compileDefinition(definition, SystemConstraints.standard())
                : compiler.compile(definition, patch, SystemConstraints.standard(), c.capabilities());
        if (result.valid() && !c.platformOperator()) {
            String profile = result.config().definition().taxBinding().taxProfileNo();
            if (profile != null && !c.verifiedTaxProfiles().contains(profile)) throw bad();
        }
        return result;
    }

    private RevisionVO append(Context c, Head head, CompilationResult compilation, String templateRevision,
            String patch, String reason, String rollback, boolean template) {
        String previous = head.draft().activeRevisionNo();
        long number = previous == null ? 1 : Math.addExact(revision(c, head.draft().no(), previous, template)
                    .revision(), 1);
        String no = BizKey.next("PR");
        String effective = compilation.config().canonicalJson();
        String hash = template ? digest(effective) : compilation.config().configHash();
        if (template) {
            var row = new OrdPricingTemplateRevision();
            audit(row, c);
            row.setRevisionNo(no);
            row.setTemplateNo(head.draft().no());
            row.setRevision(number);
            row.setIndustryCode(head.draft().industryCode());
            row.setSchemaVersion(compilation.config().definition().schemaVersion());
            row.setDefinitionSnapshot(effective);
            row.setDefinitionHash(hash);
            row.setEngineSpecVersion(PricingRuleCatalog.registryVersion(
                    compilation.config().definition().schemaVersion()));
            row.setRuleCatalogVersion(digest(canonical(compilation.config().rules())));
            row.setPreviousRevisionNo(previous);
            row.setPublishedAt(LocalDateTime.now());
            row.setPublishedBy(c.actorNo());
            row.setPublishReason(reason);
            templateRevisions.insert(row);
        } else {
            var row = new OrdStorePricingRevision();
            audit(row, c);
            row.setRevisionNo(no);
            row.setConfigNo(head.draft().no());
            row.setRevision(number);
            row.setEntityNo(c.entityNo());
            row.setStoreNo(c.storeNo());
            row.setIndustryCode(head.draft().industryCode());
            row.setTemplateRevisionNo(templateRevision);
            row.setOverrideSnapshot(patch);
            row.setEffectiveSnapshot(effective);
            row.setSchemaVersion(compilation.config().definition().schemaVersion());
            row.setEffectiveHash(hash);
            row.setEngineSpecVersion(PricingRuleCatalog.registryVersion(
                    compilation.config().definition().schemaVersion()));
            row.setRuleCatalogVersion(digest(canonical(compilation.config().rules())));
            row.setPreviousRevisionNo(previous);
            row.setRollbackFromRevisionNo(rollback);
            row.setPublishedAt(LocalDateTime.now());
            row.setPublishedBy(c.actorNo());
            row.setPublishReason(reason);
            storeRevisions.insert(row);
        }
        change(c, head, template ? effective : patch, templateRevision, no, template);
        events.publish(new PricingConfigPublished(c.tenantNo(), head.draft().no(), no, hash,
                template ? null : c.entityNo(), template ? null : c.storeNo(), c.actorNo(), template));
        return revision(c, head.draft().no(), no, template);
    }

    private int draftSchemaVersion(String definition) {
        var declared = json.readTree(definition).path("schemaVersion");
        // Incomplete drafts remain admissible; their legacy marker never grants compilation validity.
        return declared.isIntegralNumber() && declared.canConvertToInt() ? declared.asInt() : 2;
    }

    private void change(Context c, Head head, String content, String templateRevision, String active,
                    boolean template) {
        long version = head.draft().draftVersion();
        int changed;
        if (template) {
            var update = new UpdateWrapper<OrdPricingTemplate>().eq("tenant_no", c.tenantNo())
                    .eq("template_no", head.draft().no()).eq("draft_version", version).eq("status", "ACTIVE")
                    .set("draft_content", content).set("draft_schema_version", draftSchemaVersion(content))
                    .set("draft_version", Math.addExact(version, 1))
                    .set("active_revision_no", active).set("updated_at", LocalDateTime.now())
                    .set("updated_by", c.actorNo())
                    .setSql("version = version + 1");
            if (head.draft().activeRevisionNo() == null) update.isNull("active_revision_no");
            else update.eq("active_revision_no", head.draft().activeRevisionNo());
            changed = templates.update(null, update);
        } else {
            var update = new UpdateWrapper<OrdStorePricingConfig>().eq("tenant_no", c.tenantNo())
                    .eq("entity_no", c.entityNo()).eq("store_no", c.storeNo()).eq("config_no", head.draft().no())
                    .eq("draft_version", version).eq("status", "ACTIVE").set("draft_override", content)
                    .set("draft_template_revision_no", templateRevision)
                    .set("draft_schema_version", templateRevision(c, templateRevision).getSchemaVersion())
                    .set("draft_version", Math.addExact(version, 1))
                    .set("active_revision_no", active).set("updated_at", LocalDateTime.now())
                    .set("updated_by", c.actorNo())
                    .setSql("version = version + 1");
            if (head.draft().activeRevisionNo() == null) update.isNull("active_revision_no");
            else update.eq("active_revision_no", head.draft().activeRevisionNo());
            changed = stores.update(null, update);
        }
        if (changed != 1) throw conflict();
    }

    private Head head(Context c, String no, boolean template, boolean lock) {
        PricingConfigCommands.identity(no);
        if (template) {
            var query = new QueryWrapper<OrdPricingTemplate>().eq("tenant_no", c.tenantNo()).eq("template_no", no)
                    .eq("status", "ACTIVE");
            if (lock) query.last("FOR UPDATE");
            var row = templates.selectOne(query);
            if (row == null) throw missing();
            return new Head(new DraftVO(no, row.getIndustryCode(), row.getDraftVersion(), row.getActiveRevisionNo(),
                    row.getDraftContent(), null), row.getTemplateNo());
        }
        var query = new QueryWrapper<OrdStorePricingConfig>().eq("tenant_no", c.tenantNo())
                    .eq("entity_no", c.entityNo())
                .eq("store_no", c.storeNo()).eq("config_no", no).eq("status", "ACTIVE");
        if (lock) query.last("FOR UPDATE");
        var row = stores.selectOne(query);
        if (row == null) throw missing();
        return new Head(new DraftVO(no, row.getIndustryCode(), row.getDraftVersion(), row.getActiveRevisionNo(),
                row.getDraftOverride(), row.getDraftTemplateRevisionNo()), row.getTemplateNo());
    }

    private OrdPricingTemplateRevision templateRevision(Context c, String no) {
        PricingConfigCommands.identity(no);
        var row = templateRevisions.selectOne(new QueryWrapper<OrdPricingTemplateRevision>()
                .eq("tenant_no", c.tenantNo()).eq("revision_no", no));
        if (row == null) throw missing();
        var definition = compiler.compileDefinition(row.getDefinitionSnapshot(), SystemConstraints.standard());
        if (!definition.valid() || !digest(definition.config().canonicalJson()).equals(row.getDefinitionHash())
                || !PricingRuleCatalog.registryVersion(definition.config().definition().schemaVersion())
                        .equals(row.getEngineSpecVersion())
                || !digest(canonical(definition.config().rules())).equals(row.getRuleCatalogVersion())) throw bad();
        return row;
    }

    private RevisionVO revision(Context c, String parent, String no, boolean template) {
        if (template) {
            var row = templateRevision(c, no);
            if (!row.getTemplateNo().equals(parent)) throw missing();
            return new RevisionVO(no, parent, row.getRevision(), null, "{}", row.getDefinitionSnapshot(),
                    row.getDefinitionHash(), row.getPreviousRevisionNo(), null);
        }
        var row = storeRevisions.selectOne(new QueryWrapper<OrdStorePricingRevision>().eq("tenant_no", c.tenantNo())
                .eq("entity_no", c.entityNo()).eq("store_no", c.storeNo()).eq("config_no", parent)
                    .eq("revision_no", no));
        if (row == null) throw missing();
        return new RevisionVO(no, parent, row.getRevision(), row.getTemplateRevisionNo(), row.getOverrideSnapshot(),
                row.getEffectiveSnapshot(), row.getEffectiveHash(), row.getPreviousRevisionNo(),
                    row.getRollbackFromRevisionNo());
    }

    private <T> T write(Context c, String op, String no, boolean template, Object req, String key,
            Class<T> type, Supplier<T> action) {
        return transactions.execute(status -> idempotency.execute(key(key), endpoint(c, op, no, template),
                c.actorNo(), digest(canonical(req)), type, action));
    }

    private <T> T authorized(Context c, boolean template, Supplier<T> action) {
        Objects.requireNonNull(c);
        if (template != c.platformOperator()) throw BizException.of(ErrorCode.FORBIDDEN);
        // Local bypass only after trusted scope validation; every config SQL still has exact tenant/store predicates.
        return DataScopeContext.executeWithoutScope(action);
    }

    private String endpoint(Context c, String op, String no, boolean template) {
        return "PRICING:" + digest(canonical(Arrays.asList(op, no, template,
                c.tenantNo(), c.entityNo(), c.storeNo())));
    }

    private InitializeReq normalize(InitializeReq request) {
        if (request == null) throw bad();
        return new InitializeReq(request.templateRevisionNo(), request.reason(), content(request.initialOverride()));
    }

    private String content(String value) {
        if (value == null || value.length() > 65536) throw bad();
        try {
            var node = strictJson.readTree(value);
            if (!node.isObject()) throw bad();
            return canonical(node);
        } catch (JacksonException invalidJson) {
            throw bad();
        }
    }

    private String canonical(Object value) {
        JsonNode node = json.valueToTree(value);
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            var result = json.createObjectNode();
            fields.forEach((name, item) -> result.set(name, json.readTree(canonical(item))));
            return result.toString();
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(item -> result.add(json.readTree(canonical(item))));
            return result.toString();
        }
        return node.toString();
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
                }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error);
        }
    }
    private static void audit(BaseEntity row, Context c) {
        row.setTenantNo(c.tenantNo());
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        row.setCreatedBy(c.actorNo());
        row.setUpdatedBy(c.actorNo());
        row.setVersion(0L);
        row.setDeleted(0);
    }
    private static void fresh(Context c) {
        if (!Instant.now().isBefore(c.expiresAt())) throw conflict();
    }
    private static void expected(Head head, long version, String pointer) {
        if (version < 0 || head.draft().draftVersion() != version
                || !Objects.equals(head.draft().activeRevisionNo(), pointer)) throw conflict();
    }
    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw bad();
        return value;
    }
    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 256) throw bad();
    }
    private static void requireValid(CompilationResult result) { if (!result.valid()) throw bad();
    }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT);
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND);
    }
    private record Head(DraftVO draft, String templateNo) { }
    private record Prepared(PreviewVO preview, String templateRevisionNo, String override) { }
}
