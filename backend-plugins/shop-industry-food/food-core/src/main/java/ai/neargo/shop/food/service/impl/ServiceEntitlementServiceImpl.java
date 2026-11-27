package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.AttendanceConfirmation;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ConfirmAttendance;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Consume;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Dish;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Entitlement;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Extension;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ExtensionReceipt;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Plan;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.PlanRevision;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.QuoteRef;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceEntitlementMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.support.CommandBusinessKey;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.service.support.BusinessCommandJournal;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Nonfinancial operational grants, exact original-line consumption, and intended banquet plans
 * share the original admission locks.
 */
public final class ServiceEntitlementServiceImpl implements ServiceEntitlementService {
    private final ServiceEntitlementMapper mapper;
    private final ServiceFormMapper forms;
    private final FoodBusinessOperationsMapper operations;
    private final BusinessCommandJournal journal;
    private final TransactionTemplate tx;
    private final TransactionTemplate industryTx;
    private final ConfigRevisionService revisions;
    private final MaterialCatalogQueryPort materials;
    private final CatalogPriceSourcePort markets;
    private final PricingCheckoutPort quotes;
    private final CheckCartService carts;
    private final ServiceSlotService slots;
    private final StoreCardQueryPort stores;
    private final ObjectMapper json;
    private final Clock clock;

    public ServiceEntitlementServiceImpl(
            ServiceEntitlementMapper mapper,
            ServiceFormMapper forms,
            FoodBusinessOperationsMapper operations,
            PlatformTransactionManager manager,
            PlatformTransactionManager industryManager,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            CatalogPriceSourcePort markets,
            PricingCheckoutPort quotes,
            CheckCartService carts,
            ServiceSlotService slots,
            StoreCardQueryPort stores,
            ObjectMapper json,
            Clock clock) {
        this.mapper = mapper;
        this.forms = forms;
        this.operations = operations;
        this.revisions = revisions;
        this.materials = materials;
        this.markets = markets;
        this.quotes = quotes;
        this.carts = carts;
        this.slots = slots;
        this.stores = stores;
        this.json = json;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
        industryTx = new TransactionTemplate(industryManager);
        journal = new BusinessCommandJournal(operations, json);
    }

    private record QuoteEvidence(CartVO cart, QuoteVO quote) {}

    private record AllowanceOwner(
            MenuSourceContracts.Proof menu, MaterialCatalogQueryPort.MaterialFact material) {}

    private record ConsumptionEvidence(Map<String, Object> originalLine, Entitlement result) {}

    private record PlanEnvelope(
            PlanRevision plan,
            CatalogPriceSourcePort.MarketFacts market,
            List<AllowanceOwner> owners) {}

    private String planHash(
            String formNo,
            long revision,
            int persons,
            CatalogPriceSourcePort.Market market,
            long budget,
            List<Dish> dishes,
            List<AllowanceOwner> owners) {
        return PricingSnapshotHash.of(
                List.of(formNo, revision, persons, market, budget, dishes, owners));
    }

    @Override
    public Entitlement grant(
            String e, String s, String actor, String formNo, Grant req, String key) {
        require(req != null);
        scope(e, s, actor);
        text(req.reason());
        quantity(req.quantity());
        var replay =
                replay(
                        e,
                        s,
                        actor,
                        "GRANT_SERVICE_ENTITLEMENT",
                        formNo,
                        req,
                        key,
                        Entitlement.class);
        if (replay != null) return replay;
        var form = one(forms.form(e, s, formNo, false));
        activeBuffet(form);
        version(form, req.formVersion());
        var attendee = attendee(e, s, formNo, req.attendeeNo(), false);
        require("IN".equals(attendee.text("status")));
        var owner =
                allowance(e, s, form, req.menuItemNo(), req.goodsNo(), req.skuNo(), req.quantity());
        var quote = quote(e, s, actor, form, req.quote());
        return command(
                e,
                s,
                actor,
                "GRANT_SERVICE_ENTITLEMENT",
                formNo,
                req,
                key,
                Entitlement.class,
                () -> {
                    var current = lockedForm(e, s, formNo);
                    activeBuffet(current);
                    version(current, req.formVersion());
                    require(
                            "IN"
                                    .equals(
                                            attendee(e, s, formNo, req.attendeeNo(), true)
                                                    .text("status")));
                    quoteScope(e, s, current, quote, true);
                    require(owner.menu().validUntil().toInstant().isAfter(clock.instant()));
                    // 到店单为主：交易侧先校验并推进到店单版本，行业库的权益最后写、写完交易才提交。
                    // 权益号由请求键派生，提交失败后重试得到同一个号，已写过就不再写。
                    require(forms.advance(e, s, formNo, req.formVersion(), actor) == 1);
                    String no =
                            CommandBusinessKey.derive(
                                    "FSEN", "GRANT_SERVICE_ENTITLEMENT", e, s, actor, key);
                    String hash = PricingSnapshotHash.of(List.of(formNo, req.attendeeNo(), owner));
                    if (!mapper.entitlement(e, s, no, false).isEmpty())
                        return view(one(mapper.entitlement(e, s, no, false)));
                    return industryTx.execute(
                            industry -> {
                    mapper.grant(
                            e,
                            s,
                            no,
                            formNo,
                            req.attendeeNo(),
                            req.goodsNo(),
                            req.skuNo(),
                            req.menuItemNo(),
                            owner.material().baseUom(),
                            req.quantity(),
                            local(current.instant("starts_at")),
                            local(current.instant("ends_at")),
                            json.writeValueAsString(owner),
                            quote.quote().quoteNo(),
                            PricingSnapshotHash.of(quote.quote()),
                            hash,
                            actor);
                    var result = view(one(mapper.entitlement(e, s, no, false)));
                    mapper.event(
                            e,
                            s,
                            CommandBusinessKey.derive(
                                    "FSEE", "GRANT_SERVICE_ENTITLEMENT_EVENT", e, s, actor, key),
                            formNo,
                            no,
                            "GRANT",
                            req.quantity(),
                            null,
                            null,
                            quote.quote().quoteNo(),
                            PricingSnapshotHash.of(quote.quote()),
                            json.writeValueAsString(Arrays.asList(owner, quote, result)),
                            actor,
                            req.reason());
                    return result;
                            });
                });
    }

    // 消耗是权益（行业库）上的事：在行业事务里锁权益、扣数量、记事件，不用永久命令回执。
    // 一行点菜只能扣一次，「批次 + 点菜行」就是业务键：重放（换不换请求键都一样）查回首次结果；
    // 到店单、账单、点菜行在交易库，只读一次，不跨库加锁。
    @Override
    public Entitlement consume(
            String e, String s, String actor, String no, Consume req, String key) {
        require(req != null);
        scope(e, s, actor);
        text(req.reason());
        textKey(key);
        CartInputPolicy.key(req.batchNo());
        CartInputPolicy.key(req.foodLineNo());
        var before = one(mapper.entitlement(e, s, no, false));
        String formNo = before.text("form_no");
        var prior = consumed(no, actor, mapper.eventByLine(e, s, req.batchNo(), req.foodLineNo()));
        if (prior != null) return prior;
        var form = one(forms.form(e, s, formNo, false));
        activeBuffet(form);
        require("IN".equals(attendee(e, s, formNo, before.text("attendee_no"), false).text("status")));
        open(one(forms.check(e, s, form.text("check_no"), false)));
        var line = one(forms.line(e, s, req.batchNo(), req.foodLineNo()));
        require(
                "ACCEPTED".equals(line.text("batch_status"))
                        && before.text("goods_no").equals(line.text("goods_no"))
                        && before.text("sku_no").equals(line.text("sku_no"))
                        && SourceQuantityUnits.same(before.text("unit"), line.text("unit")));
        var owners =
                forms.batchLineOwners(e, s, req.batchNo()).stream()
                        .filter(l -> req.foodLineNo().equals(l.text("food_line_no")))
                        .toList();
        require(
                owners.size() == 1
                        && form.text("check_no").equals(owners.getFirst().text("current_check_no")));
        require(formNo.equals(one(forms.batchBinding(e, s, req.batchNo())).text("form_no")));
        BigDecimal quantity = line.decimal("qty");
        quantity(quantity);
        try {
            return industryTx.execute(
                    status -> {
                        var entitlement = one(mapper.entitlement(e, s, no, true));
                        var again =
                                consumed(
                                        no,
                                        actor,
                                        mapper.eventByLine(e, s, req.batchNo(), req.foodLineNo()));
                        if (again != null) return again;
                        version(entitlement, req.entitlementVersion());
                        require(
                                !clock.instant().isBefore(entitlement.instant("valid_from"))
                                        && clock.instant()
                                                .isBefore(entitlement.instant("valid_until")));
                        require(
                                mapper.consume(
                                                e,
                                                s,
                                                no,
                                                req.entitlementVersion(),
                                                quantity,
                                                actor)
                                        == 1);
                        var result = view(one(mapper.entitlement(e, s, no, false)));
                        mapper.event(
                                e,
                                s,
                                CommandBusinessKey.derive(
                                        "FSEE", "CONSUME_SERVICE_ENTITLEMENT", e, s, actor, key),
                                formNo,
                                no,
                                "CONSUME",
                                quantity,
                                req.batchNo(),
                                req.foodLineNo(),
                                entitlement.text("quote_no"),
                                entitlement.text("quote_hash"),
                                json.writeValueAsString(
                                        new ConsumptionEvidence(line.snapshot(), result)),
                                actor,
                                req.reason());
                        return result;
                    });
        } catch (DuplicateKeyException concurrent) {
            // 同一请求并发重放：另一路已经记了事件，查回它。
            var replayed =
                    consumed(no, actor, mapper.eventByLine(e, s, req.batchNo(), req.foodLineNo()));
            require(replayed != null);
            return replayed;
        }
    }

    /** 这一行点菜已经扣过：必须是同一项权益、同一个操作人，返回当时的结果。 */
    private Entitlement consumed(String no, String actor, List<FoodBusinessOperationRow> prior) {
        if (prior.isEmpty()) return null;
        var event = one(prior);
        require(no.equals(event.text("entitlement_no")) && actor.equals(event.text("actor_no")));
        return json.readValue(event.text("evidence_snapshot"), ConsumptionEvidence.class).result();
    }

    @Override
    public List<Entitlement> entitlements(String e, String s, String formNo) {
        CartInputPolicy.key(e);
        CartInputPolicy.key(s);
        one(forms.form(e, s, formNo, false));
        return mapper.entitlements(e, s, formNo).stream().map(this::view).toList();
    }

    @Override
    public ExtensionReceipt extend(
            String e, String s, String actor, String formNo, Extension req, String key) {
        require(req != null);
        scope(e, s, actor);
        text(req.reason());
        require(req.validUntil() != null && req.validUntil().getNano() % 1_000_000 == 0);
        var replay =
                replay(
                        e,
                        s,
                        actor,
                        "EXTEND_SERVICE_ENTITLEMENT",
                        formNo,
                        req,
                        key,
                        ExtensionReceipt.class);
        if (replay != null) return replay;
        var form = one(forms.form(e, s, formNo, false));
        activeBuffet(form);
        version(form, req.formVersion());
        require(req.validUntil().isAfter(form.instant("ends_at")));
        var quote = quote(e, s, actor, form, req.quote());
        return command(
                e,
                s,
                actor,
                "EXTEND_SERVICE_ENTITLEMENT",
                formNo,
                req,
                key,
                ExtensionReceipt.class,
                () -> {
                    var current = lockedForm(e, s, formNo);
                    activeBuffet(current);
                    version(current, req.formVersion());
                    quoteScope(e, s, current, quote, true);
                    var slot = slots.get(e, s, req.capacitySlotNo());
                    require(
                            current.instant("ends_at").equals(slot.startsAt())
                                    && req.validUntil().equals(slot.endsAt())
                                    && Set.of("PERSON", "ORDER").contains(slot.unit()));
                    // 到店单为主：时段占用、到店单延长先在交易侧做；行业库的权益延长与事件最后写。
                    // 事件号由请求键派生，提交失败后重试不会再延长一次。
                    String event =
                            CommandBusinessKey.derive(
                                    "FSEE", "EXTEND_SERVICE_ENTITLEMENT", e, s, actor, key);
                    var claim =
                            slots.reserve(
                                    e,
                                    s,
                                    actor,
                                    new ClaimReq(
                                            slot.slotNo(),
                                            event,
                                            "PERSON".equals(slot.unit())
                                                    ? BigDecimal.valueOf(
                                                            current.number("actual_persons"))
                                                    : BigDecimal.ONE),
                                    "SERVICE_EXTENSION:" + event);
                    var used =
                            slots.transition(
                                    e,
                                    s,
                                    actor,
                                    claim.claimNo(),
                                    claim.version(),
                                    "CONSUME",
                                    "SERVICE_EXTENSION_CONSUME:" + event);
                    require("CONSUMED".equals(used.status()));
                    require(
                            forms.extendForm(
                                            e,
                                            s,
                                            formNo,
                                            req.formVersion(),
                                            local(current.instant("ends_at")),
                                            local(req.validUntil()),
                                            actor)
                                    == 1);
                    var receipt =
                            new ExtensionReceipt(
                                    event,
                                    formNo,
                                    current.instant("ends_at"),
                                    req.validUntil(),
                                    used.claimNo(),
                                    quote.quote().quoteNo(),
                                    Math.addExact(req.formVersion(), 1));
                    if (mapper.eventByNo(e, s, event).isEmpty())
                        industryTx.executeWithoutResult(
                                industry -> {
                                    mapper.extendEntitlements(
                                            e,
                                            s,
                                            formNo,
                                            local(current.instant("ends_at")),
                                            local(req.validUntil()));
                                    mapper.event(
                                            e,
                                            s,
                                            event,
                                            formNo,
                                            null,
                                            "EXTEND",
                                            BigDecimal.valueOf(current.number("actual_persons")),
                                            null,
                                            null,
                                            quote.quote().quoteNo(),
                                            PricingSnapshotHash.of(quote.quote()),
                                            json.writeValueAsString(
                                                    Arrays.asList(receipt, used, quote)),
                                            actor,
                                            req.reason());
                                });
                    return receipt;
                });
    }

    @Override
    public PlanRevision revisePlan(
            String e, String s, String actor, String formNo, Plan req, String key) {
        require(req != null);
        scope(e, s, actor);
        text(req.reason());
        require(
                req.plannedPersons() > 0
                        && req.plannedPersons() <= 1000
                        && req.intendedBudgetMinor() >= 0
                        && req.intendedBudgetMinor() <= 9007199254740991L
                        && req.dishes() != null
                        && !req.dishes().isEmpty()
                        && req.dishes().size() <= 100);
        var replay =
                replay(e, s, actor, "REVISE_BANQUET_PLAN", formNo, req, key, PlanRevision.class);
        if (replay != null) return replay;
        var form = one(forms.form(e, s, formNo, false));
        banquetBooked(form);
        version(form, req.formVersion());
        var marketScope = new CatalogPriceSourcePort.MarketScope(e, s);
        var market = markets.market(marketScope);
        CatalogPriceSourceFacts.validateMarketFacts(marketScope, market, clock.instant());
        var dishes = new ArrayList<Dish>();
        var owners = new ArrayList<AllowanceOwner>();
        var seen = new HashSet<List<String>>();
        for (var dish : req.dishes()) {
            require(dish != null && seen.add(List.of(dish.menuItemNo(), dish.skuNo())));
            quantity(dish.quantity());
            var owner =
                    allowance(
                            e,
                            s,
                            form,
                            dish.menuItemNo(),
                            dish.goodsNo(),
                            dish.skuNo(),
                            dish.quantity());
            require(SourceQuantityUnits.same(dish.unit(), owner.material().baseUom()));
            owners.add(owner);
            dishes.add(
                    new Dish(
                            dish.menuItemNo(),
                            dish.goodsNo(),
                            dish.skuNo(),
                            dish.quantity(),
                            owner.material().baseUom()));
        }
        return command(
                e,
                s,
                actor,
                "REVISE_BANQUET_PLAN",
                formNo,
                req,
                key,
                PlanRevision.class,
                () -> {
                    var current = lockedForm(e, s, formNo);
                    banquetBooked(current);
                    version(current, req.formVersion());
                    require(
                            market.validUntil().isAfter(clock.instant())
                                    && owners.stream()
                                            .allMatch(
                                                    o ->
                                                            o.menu()
                                                                    .validUntil()
                                                                    .toInstant()
                                                                    .isAfter(clock.instant())));
                    // 到店单为主：先推进到店单版本，宴会方案修订（行业库）最后写；方案号由请求键派生。
                    require(forms.advance(e, s, formNo, req.formVersion(), actor) == 1);
                    String no =
                            CommandBusinessKey.derive(
                                    "FBPL", "REVISE_BANQUET_PLAN", e, s, actor, key);
                    var written = mapper.plan(e, s, formNo, no);
                    if (!written.isEmpty())
                        return json.readValue(
                                        one(written).text("plan_snapshot"), PlanEnvelope.class)
                                .plan();
                    long revision = mapper.nextPlanRevision(e, s, formNo);
                    String hash =
                            PricingSnapshotHash.of(
                                    List.of(
                                            formNo,
                                            revision,
                                            req.plannedPersons(),
                                            market.market(),
                                            req.intendedBudgetMinor(),
                                            dishes,
                                            owners));
                    var result =
                            new PlanRevision(
                                    no,
                                    formNo,
                                    revision,
                                    req.plannedPersons(),
                                    market.market().currency(),
                                    req.intendedBudgetMinor(),
                                    dishes,
                                    hash,
                                    Math.addExact(req.formVersion(), 1));
                    industryTx.executeWithoutResult(
                            industry ->
                                    mapper.plan(
                                            e,
                                            s,
                                            formNo,
                                            no,
                                            revision,
                                            result.currency(),
                                            req.intendedBudgetMinor(),
                                            req.plannedPersons(),
                                            json.writeValueAsString(
                                                    new PlanEnvelope(result, market, owners)),
                                            hash,
                                            actor,
                                            req.reason()));
                    return result;
                });
    }

    @Override
    public AttendanceConfirmation confirmAttendance(
            String e, String s, String actor, String formNo, ConfirmAttendance req, String key) {
        require(req != null);
        scope(e, s, actor);
        text(req.reason());
        if (req.depositRef() != null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var replay =
                replay(
                        e,
                        s,
                        actor,
                        "CONFIRM_BANQUET_ATTENDANCE",
                        formNo,
                        req,
                        key,
                        AttendanceConfirmation.class);
        if (replay != null) return replay;
        var form = one(forms.form(e, s, formNo, false));
        require("BANQUET".equals(form.text("kind")) && "ACTIVE".equals(form.text("status")));
        version(form, req.formVersion());
        var plan = one(mapper.plan(e, s, formNo, req.planNo()));
        var envelope = json.readValue(plan.text("plan_snapshot"), PlanEnvelope.class);
        var revision = envelope.plan();
        require(
                revision.contentHash()
                        .equals(
                                planHash(
                                        formNo,
                                        revision.revision(),
                                        revision.plannedPersons(),
                                        envelope.market().market(),
                                        revision.intendedBudgetMinor(),
                                        revision.dishes(),
                                        envelope.owners())));
        require(
                revision.contentHash().equals(plan.text("content_hash"))
                        && revision.revision() == mapper.nextPlanRevision(e, s, formNo) - 1);
        var quote = quote(e, s, actor, form, req.quote());
        require(revision.currency().equals(quote.quote().currency()));
        for (var selected : quote.cart().lines())
            require(
                    revision.dishes().stream()
                            .anyMatch(
                                    d ->
                                            d.menuItemNo().equals(selected.menuItemNo())
                                                    && d.goodsNo().equals(selected.goodsNo())
                                                    && d.skuNo().equals(selected.skuNo())));
        return command(
                e,
                s,
                actor,
                "CONFIRM_BANQUET_ATTENDANCE",
                formNo,
                req,
                key,
                AttendanceConfirmation.class,
                () -> {
                    var current = lockedForm(e, s, formNo);
                    version(current, req.formVersion());
                    require(
                            "BANQUET".equals(current.text("kind"))
                                    && "ACTIVE".equals(current.text("status")));
                    quoteScope(e, s, current, quote, true);
                    require(revision.revision() == mapper.nextPlanRevision(e, s, formNo) - 1);
                    var roster = forms.attendees(e, s, formNo, true);
                    require(!roster.isEmpty() && roster.size() == current.number("actual_persons"));
                    String rosterHash = PricingSnapshotHash.of(roster);
                    String quoteHash = PricingSnapshotHash.of(quote.quote());
                    // 到店单为主：先推进到店单版本，到场确认（行业库）最后写；确认号由请求键派生，
                    // 提交失败后重试时已写过就不再写。
                    require(forms.advance(e, s, formNo, req.formVersion(), actor) == 1);
                    String no =
                            CommandBusinessKey.derive(
                                    "FBAC", "CONFIRM_BANQUET_ATTENDANCE", e, s, actor, key);
                    long next = Math.addExact(req.formVersion(), 1);
                    if (mapper.confirmationByNo(e, s, no).isEmpty())
                        industryTx.executeWithoutResult(
                                industry ->
                    mapper.confirmation(
                            e,
                            s,
                            formNo,
                            no,
                            req.planNo(),
                            req.formVersion(),
                            roster.size(),
                            json.writeValueAsString(roster),
                            rosterHash,
                            quote.quote().quoteNo(),
                            quoteHash,
                            json.writeValueAsString(quote),
                            actor,
                            req.reason()));
                    return new AttendanceConfirmation(
                            no,
                            formNo,
                            req.planNo(),
                            roster.size(),
                            rosterHash,
                            quote.quote().quoteNo(),
                            quoteHash,
                            quote.quote().currency(),
                            quote.quote().totals().receivableMinor(),
                            next);
                });
    }

    private AllowanceOwner allowance(
            String e,
            String s,
            FoodBusinessOperationRow form,
            String item,
            String goods,
            String sku,
            BigDecimal quantity) {
        CartInputPolicy.key(item);
        CartInputPolicy.key(goods);
        CartInputPolicy.key(sku);
        var query =
                new MenuSourceContracts.Query(
                        e,
                        s,
                        form.text("menu_no"),
                        form.text("menu_revision_no"),
                        MenuSourceContracts.Kind.SKU,
                        sku,
                        item,
                        null,
                        MenuSourceContracts.Mode.HISTORICAL);
        var proof = revisions.verifyMenuResource(query);
        MenuSourcePolicy.validateProof(
                json, query, proof, clock.instant().atOffset(ZoneOffset.UTC));
        require(
                PublicationDocuments.hash(json, proof.publication().definitionSnapshot())
                        .equals(PublicationDocuments.hash(json, form.text("menu_snapshot"))));
        var menu =
                json.readValue(
                        proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        require(
                menu.skuFacts().stream()
                        .anyMatch(f -> sku.equals(f.skuNo()) && goods.equals(f.goodsNo())));
        var facts = materials.getSkuMaterials(e, s, List.of(sku));
        require(facts != null && facts.size() == 1 && sku.equals(facts.getFirst().skuNo()));
        var material = facts.getFirst().material();
        require(
                ai.neargo.shop.food.policy.FoodMaterialFacts.validFact(material)
                        && e.equals(material.entityNo())
                        && (quantity.stripTrailingZeros().scale() <= 0
                                || Integer.valueOf(1).equals(material.weighed())));
        return new AllowanceOwner(proof, material);
    }

    private QuoteEvidence quote(
            String e, String s, String actor, FoodBusinessOperationRow form, QuoteRef ref) {
        require(ref != null);
        CartInputPolicy.key(ref.cartNo());
        CartInputPolicy.key(ref.quoteNo());
        require(
                ref.cartVersion() >= 0
                        && ref.confirmationDigest() != null
                        && ref.confirmationDigest().matches("[a-f0-9]{64}"));
        var cart = carts.getCart(e, s, ref.cartNo()).cart();
        require(
                cart != null
                        && cart.version() == ref.cartVersion()
                        && Objects.equals(form.text("check_no"), cart.checkNo())
                        && "DINE_IN".equals(cart.fulfillment()));
        var quote =
                quotes.revalidateStaff(
                        new Scope("MAIN", e, s),
                        actor,
                        new PricingSourceRef(
                                "FOOD", "CART", ref.cartNo(), ref.cartVersion(), null, null),
                        ref.quoteNo(),
                        ref.confirmationDigest());
        require(
                quote != null
                        && ref.quoteNo().equals(quote.quoteNo())
                        && "READY".equals(quote.status())
                        && ref.confirmationDigest().equals(quote.confirmationDigest())
                        && quote.totals() != null
                        && quote.totals().receivableMinor() >= 0
                        && quote.sourceHash() != null
                        && quote.sourceHash().matches("[a-f0-9]{64}")
                        && Instant.parse(quote.expiresAt()).isAfter(clock.instant()));
        return new QuoteEvidence(cart, quote);
    }

    private void quoteScope(
            String e,
            String s,
            FoodBusinessOperationRow form,
            QuoteEvidence evidence,
            boolean lock) {
        var check = one(forms.check(e, s, form.text("check_no"), lock));
        open(check);
        require(
                check.number("party_size") == form.number("actual_persons")
                        && Instant.parse(evidence.quote().expiresAt()).isAfter(clock.instant()));
        var cart = one(forms.cart(e, s, evidence.cart().cartNo(), lock));
        require(
                form.text("check_no").equals(cart.text("check_no"))
                        && cart.number("version") == evidence.cart().version()
                        && "OPEN".equals(cart.text("status")));
    }

    private FoodBusinessOperationRow lockedForm(String e, String s, String no) {
        var before = one(forms.form(e, s, no, false));
        if (before.text("check_no") != null) one(forms.check(e, s, before.text("check_no"), true));
        var current = one(forms.form(e, s, no, true));
        require(Objects.equals(before.text("check_no"), current.text("check_no")));
        return current;
    }

    private FoodBusinessOperationRow attendee(
            String e, String s, String form, String attendee, boolean lock) {
        CartInputPolicy.key(attendee);
        return forms.attendees(e, s, form, lock).stream()
                .filter(a -> attendee.equals(a.text("attendee_no")))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private void activeBuffet(FoodBusinessOperationRow form) {
        require(
                "BUFFET".equals(form.text("kind"))
                        && "ACTIVE".equals(form.text("status"))
                        && !clock.instant().isBefore(form.instant("starts_at"))
                        && clock.instant().isBefore(form.instant("ends_at")));
    }

    private static void banquetBooked(FoodBusinessOperationRow form) {
        require("BANQUET".equals(form.text("kind")) && "BOOKED".equals(form.text("status")));
    }

    private static void open(FoodBusinessOperationRow check) {
        require("OPEN".equals(check.text("status")) && "OPEN".equals(check.text("service_state")));
    }

    private static void version(FoodBusinessOperationRow row, long version) {
        require(version >= 0 && row.number("version") == version);
    }

    private Entitlement view(FoodBusinessOperationRow r) {
        return new Entitlement(
                r.text("entitlement_no"),
                r.text("form_no"),
                r.text("attendee_no"),
                r.text("goods_no"),
                r.text("sku_no"),
                r.decimal("granted_qty"),
                r.decimal("consumed_qty"),
                r.text("unit"),
                r.instant("valid_from"),
                r.instant("valid_until"),
                r.text("quote_no"),
                r.text("source_hash"),
                r.number("version"));
    }

    private void scope(String e, String s, String actor) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        CartInputPolicy.key(e);
        CartInputPolicy.key(s);
        CartInputPolicy.key(actor);
        var card = stores.cards(List.of(s)).get(s);
        if (card == null || !e.equals(card.entityNo()) || !card.active())
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private <T> T replay(
            String e,
            String s,
            String actor,
            String operation,
            String source,
            Object req,
            String key,
            Class<T> type) {
        textKey(key);
        String scope = FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(operation, e, s));
        var old =
                operations.commandReceiptSelectBusinessCommand(
                        scope, FoodCommandFingerprint.hash(key));
        if (old.isEmpty()) return null;
        var row = one(old);
        require(
                actor.equals(row.text("actor_no"))
                        && PricingSnapshotHash.of(List.of(source, req))
                                .equals(row.text("request_hash"))
                        && row.text("result_json") != null);
        return json.readValue(row.text("result_json"), type);
    }

    private <T> T command(
            String e,
            String s,
            String actor,
            String operation,
            String source,
            Object req,
            String key,
            Class<T> type,
            Supplier<T> work) {
        return tx.execute(
                status ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope(operation, e, s),
                                actor,
                                PricingSnapshotHash.of(List.of(source, req)),
                                type,
                                work));
    }

    private static FoodBusinessOperationRow one(List<FoodBusinessOperationRow> rows) {
        require(rows != null && rows.size() == 1);
        return rows.getFirst();
    }

    private static void quantity(BigDecimal value) {
        InventoryDemandPlanFacts.quantity(value, false);
        require(value.compareTo(new BigDecimal("999999")) <= 0);
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static void text(String value) {
        require(value != null && !value.isBlank() && value.length() <= 255);
    }

    private static void textKey(String value) {
        require(value != null && !value.isBlank() && value.length() <= 128);
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
