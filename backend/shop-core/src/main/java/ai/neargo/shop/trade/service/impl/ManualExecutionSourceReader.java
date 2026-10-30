package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.trade.service.ServiceExecutionService.ExecutionCoordinates;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutEconomicSnapshot;
import ai.neargo.shop.trade.order.service.create.CounterEconomicCodec;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan.PreparedCommand;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashSet;

import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceLine;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;

import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reads immutable source evidence shared by admission and execution without introducing another ledger. */
final class ManualExecutionSourceReader {
    private final ObjectMapper json;
    private final CheckoutMappers.QuoteMapper quotes;
    private final CheckoutMappers.ConfirmationMapper confirmations;
    private final CheckoutMappers.LineMapper mappings;
    private final OrderItemMapper items;
    private final ManualAdmissionMapper declarations;

    /** The owner's mapper keeps existing record serialization and digest bytes unchanged. */
    ManualExecutionSourceReader(ObjectMapper json, CheckoutMappers.QuoteMapper quotes,
            CheckoutMappers.ConfirmationMapper confirmations, CheckoutMappers.LineMapper mappings,
            OrderItemMapper items, ManualAdmissionMapper declarations) {
        this.json = Objects.requireNonNull(json, "json");
        this.quotes = Objects.requireNonNull(quotes, "quotes");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.items = Objects.requireNonNull(items, "items");
        this.declarations = Objects.requireNonNull(declarations, "declarations");
    }

    /** Reuse the exact legacy evidence ordering so execution cannot invent a competing source hash. */
    String sourceHash(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child,
            OrdManualServiceAdmission declaration, List<OrdCheckoutLine> sourceRows,
            List<OrdItem> originalItems, String state, List<ManualSourceLine> lines) {
    var mappingVersions =
            sourceRows.stream()
                    .sorted(
                            Comparator.comparing(OrdCheckoutLine::getSourceLineNo)
                                    .thenComparing(OrdCheckoutLine::getId))
                    .map(
                            line ->
                                    new MappingRevision(
                                            line.getSourceLineNo(),
                                            line.getId(),
                                            line.getVersion()))
                    .toList();
    var itemVersions =
            originalItems.stream()
                    .sorted(Comparator.comparing(OrdItem::getId))
                    .map(item -> new ItemRevision(item.getId(), item.getVersion()))
                    .toList();
    String observed =
            CheckoutDigests.hash(
                    json,
                    Arrays.asList(
                            "MANUAL_SOURCE_V1",
                            op.getOperationNo(),
                            op.getBuyerNo(),
                            op.getOrderNo(),
                            op.getSubOrderNo(),
                            parent == null ? 0 : parent.getVersion(),
                            child == null ? 0 : child.getVersion(),
                            declaration == null ? 0 : declaration.getAdmissionRevision(),
                            mappingVersions,
                            itemVersions,
                            state,
                            lines));
    if ("MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())) {
        observed =
                CheckoutDigests.hash(
                        json,
                        Arrays.asList(
                                "MANUAL_COUNTER_SOURCE_V1",
                                party(op),
                                op.getConfirmationNo(),
                                op.getAuthorityNamespaceHash(),
                                observed));
    }        return observed;
    }

    private static CustomerParty party(OrdCheckoutOperation op) {
        return new CustomerParty(op.getCustomerKind(), op.getBuyerNo(), op.getGuestNo(), op.getIdentityRevision());
    }

    private record MappingRevision(String sourceLineNo, Long id, Long revision) {}

    private record ItemRevision(Long id, Long revision) {}

    /** Validate the same consumed purchase and immutable lines for receipt and execution consumers. */
    Facts load(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child) {
        if (parent == null
                || child == null
                || !"CREATED".equals(op.getPhase())
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(parent.getAdmissionKind())
                || !"MANUAL_OFFLINE".equals(child.getAdmissionKind())
                || !Objects.equals(parent.getOrderNo(), child.getOrderNo())
                || !party(op)
                        .equals(
                                new CustomerParty(
                                        parent.getCustomerKind(),
                                        parent.getUserNo(),
                                        parent.getGuestNo(),
                                        parent.getIdentityRevision()))
                || !party(op)
                        .equals(
                                new CustomerParty(
                                        child.getCustomerKind(),
                                        child.getUserNo(),
                                        child.getGuestNo(),
                                        child.getIdentityRevision()))
                || "MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())
                        && (!op.getEntityNo().equals(parent.getEntityNo())
                                || !op.getStoreNo().equals(parent.getStoreNo())
                                || !op.getCaller().equals(parent.getCaller())
                                || !op.getOperationNo().equals(parent.getOperationNo())
                                || !op.getCaller().equals(child.getCaller()))
                || !op.getEntityNo().equals(child.getEntityNo())
                || !op.getStoreNo().equals(child.getStoreNo())
                || !op.getOperationNo().equals(child.getSourceOperationNo())
                || !OrdSubOrder.SERVICE_EXECUTION.equals(child.getFulfillmentAuthority())
                || !"STORE_VERIFY".equals(child.getFulfillment())
                || parent.getPayChannel() != null
                || parent.getPayTradeNo() != null
                || Boolean.TRUE.equals(parent.getPointsIntegrated())
                || child.getGroupNo() != null
                || child.getPeriodNo() != null
                || nz(child.getPointsDeductMinor()) != 0
                || nz(child.getDiscountPlatform()) != 0
                || declarations.afterSaleConflicts(child.getSubOrderNo()) != 0
                || declarations.financialConflicts(parent.getOrderNo()) != 0
                || !mappings.refundWork(child.getSubOrderNo()).isEmpty()) {
            throw conflict();
        }
        var quote = quotes.find(op.getCurrentQuoteNo());
        if (quote == null || !"CONSUMED".equals(quote.getStatus())) {
            throw conflict();
        }
        CheckoutEconomicSnapshot economics;
        try {
            if ("MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())) {
                economics = new CounterEconomicCodec(json).readQuote(quote.getPricingJson());
            } else {
                var raw = json.readTree(quote.getPricingJson());
                if (raw.has("schema") || raw.has("economic") || raw.has("promotions")
                        || raw.has("counterActivitySeal")) {
                    throw conflict();
                }
                economics = json.readValue(quote.getPricingJson(), CheckoutEconomicSnapshot.class);
            }
        } catch (RuntimeException invalid) {
            throw conflict();
        }
        if (!Objects.equals(op.getBuyerNo(), economics.buyerNo())
                || !op.getEntityNo().equals(economics.entityNo())
                || !op.getStoreNo().equals(economics.storeNo())) {
            throw conflict();
        }
        var sourceLines =
                mappings.selectList(
                        Wrappers.<OrdCheckoutLine>lambdaQuery()
                                .eq(OrdCheckoutLine::getTenantNo, "MAIN")
                                .eq(OrdCheckoutLine::getCaller, op.getCaller())
                                .eq(OrdCheckoutLine::getOperationNo, op.getOperationNo())
                                .orderByAsc(OrdCheckoutLine::getOrderItemId));
        var originalItems =
                items.selectList(
                        Wrappers.<OrdItem>lambdaQuery()
                                .eq(OrdItem::getTenantNo, "MAIN")
                                .eq(OrdItem::getSubOrderNo, child.getSubOrderNo())
                                .orderByAsc(OrdItem::getId));
        requireAdmissionEconomics(op, parent, child, quote, economics, sourceLines, originalItems);
        if (sourceLines.isEmpty()
                || sourceLines.size() != originalItems.size()
                || economics.lines().size() != sourceLines.size()
                || economics.dueMinor() <= 0
                || !Objects.equals(parent.getPayAmount(), economics.dueMinor())
                || !Objects.equals(child.getPayAmount(), economics.dueMinor())) {
            throw conflict();
        }
        for (var line : sourceLines) {
            var item =
                    originalItems.stream()
                            .filter(value -> value.getId().equals(line.getOrderItemId()))
                            .findFirst()
                            .orElseThrow(this::conflict);
            var priced =
                    economics.lines().stream()
                            .filter(value -> value.clientLineRef().equals(line.getClientLineRef()))
                            .findFirst()
                            .orElseThrow(this::conflict);
            if (!child.getSubOrderNo().equals(line.getSubOrderNo())
                    || !"COUNT".equals(line.getUom())
                    || !line.getSkuNo().equals(item.getSkuNo())
                    || !line.getGoodsNo().equals(item.getGoodsNo())
                    || !Objects.equals(line.getQuantity(), item.getQty())
                    || line.getQuantity() != priced.quantity()
                    || !line.getSkuNo().equals(priced.skuNo())
                    || !Objects.equals(item.getPrice(), priced.unitPriceMinor())
                    || !Objects.equals(item.getAmount(), priced.grossMinor())
                    || !Objects.equals(item.getPaidShareMinor(), priced.dueMinor())
                    || Boolean.TRUE.equals(item.getIsGift())
                    || nz(item.getRefundedMinor()) != 0
                    || nz(item.getRefundedQty()) != 0) {
                throw conflict();
            }
        }
        return new Facts(op, parent, child, quote, sourceLines, originalItems);
    }

    private void requireAdmissionEconomics(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child,
            OrdCheckoutQuote quote, CheckoutEconomicSnapshot economics, List<OrdCheckoutLine> sourceLines,
            List<OrdItem> originalItems) {
        boolean counter = "MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority());
        if (!counter && !"CONSUMER_SESSION".equals(op.getPurchaseAuthority())) {
            throw conflict();
        }
        if (!counter || economics.counterActivitySeal() == null) {
            if (economics.counterActivitySeal() != null || economics.discountMinor() != 0
                    || economics.platformMinor() != 0 || economics.merchantMinor() != 0
                    || economics.freightMinor() != 0 || economics.dueMinor() != economics.goodsMinor()
                    || !CampaignPort.Discount.none().equals(economics.activity())
                    || !CouponPort.Allocation.none().equals(economics.coupon())
                    || nz(child.getDiscountMerchant()) != 0) {
                throw conflict();
            }
            if (counter) {
                var prepared = json.readValue(op.getCommandJson(), PreparedCommand.class);
                var codec = new CounterEconomicCodec(json);
                if (!codec.legacyZero(economics, prepared.command())) {
                    throw conflict();
                }
                codec.requireQuoteHash(party(op), prepared.lines(), economics, quote.getQuoteHash());
            }
            return;
        }
        try {
            for (var row : List.of(op, parent, child, quote)) {
                alive(row);
            }
            sourceLines.forEach(this::alive);
            originalItems.forEach(this::alive);
            var prepared = json.readValue(op.getCommandJson(), PreparedCommand.class);
            var command = prepared.command();
            if (command.couponNo() != null || command.usePoints() != null && command.usePoints() != 0
                    || command.grouped() || command.appointmentAt() != null || command.appointmentSlotNo() != null
                    || !"STORE_VERIFY".equals(command.fulfillment()) || !"OFFLINE".equals(command.payMode())
                    || command.pickupNo() != null || command.addressId() != null
                    || command.items() == null || command.items().size() != economics.lines().size()) {
                throw conflict();
            }
            for (int index = 0; index < command.items().size(); index++) {
                var requested = command.items().get(index);
                var priced = economics.lines().get(index);
                if (!requested.skuNo().equals(priced.skuNo()) || requested.qty() != priced.quantity()
                        || requested.goodsNo() != null && !requested.goodsNo().equals(priced.goodsNo())) {
                    throw conflict();
                }
            }
            var codec = new CounterEconomicCodec(json);
            codec.requireQuoteHash(party(op), prepared.lines(), economics, quote.getQuoteHash());
            var seal = economics.counterActivitySeal();
            CounterCampaignContract.requireSelection(json, seal, party(op), seal.groups(),
                    prepared.command().activityChoices(), economics.activity());
            var evidence = economics.evidence();
            if (!op.getOrderNo().equals(parent.getOrderNo()) || !op.getSubOrderNo().equals(child.getSubOrderNo())
                    || !op.getCurrentQuoteNo().equals(quote.getQuoteNo())
                    || !op.getCaller().equals(quote.getCaller())
                    || !op.getOperationNo().equals(quote.getOperationNo())
                    || !"MAIN".equals(evidence.tenantNo()) || evidence.scopeRevision() != op.getScopeRevision()
                    || !op.getScopeHash().equals(evidence.scopeHash())
                    || !"CNY".equals(economics.currency()) || !"CNY".equals(parent.getCurrency())
                    || !"OFFLINE".equals(economics.payMode())
                    || !"STORE_VERIFY".equals(economics.fulfillment())
                    || !OrdSubOrder.SERVICE_EXECUTION.equals(economics.fulfillmentAuthority())) {
                throw conflict();
            }
            OrdCheckoutConfirmation consent = confirmations.selectOne(
                    Wrappers.<OrdCheckoutConfirmation>lambdaQuery()
                            .eq(OrdCheckoutConfirmation::getTenantNo, "MAIN")
                            .eq(OrdCheckoutConfirmation::getConfirmationNo, op.getConfirmationNo())
                            .eq(OrdCheckoutConfirmation::getDeleted, 0));
            if (consent == null) {
                throw conflict();
            }
            alive(consent);
            if (!"CONSUMED".equals(consent.getStatus())
                    || !"ORDINARY_SERVICE_PURCHASE".equals(consent.getPurpose())
                    || !"MANUAL_OFFLINE".equals(consent.getAdmissionKind())
                    || !op.getPurchaseAuthority().equals(consent.getPurchaseAuthority())
                    || !op.getCaller().equals(consent.getCaller())
                    || !op.getOperationNo().equals(consent.getOperationNo())
                    || !op.getEntityNo().equals(consent.getEntityNo())
                    || !op.getStoreNo().equals(consent.getStoreNo())
                    || !party(op).equals(new CustomerParty(consent.getCustomerKind(), consent.getBuyerNo(),
                            consent.getGuestNo(), consent.getIdentityRevision()))
                    || op.getAuthorityNamespaceHash() == null
                    || !op.getAuthorityNamespaceHash().equals(consent.getAuthorityNamespaceHash())
                    || !quote.getQuoteNo().equals(consent.getQuoteNo())
                    || !quote.getRevision().equals(consent.getQuoteRevision())
                    || !quote.getQuoteHash().equals(consent.getQuoteHash())
                    || !parent.getOrderNo().equals(consent.getOrderNo())
                    || !Long.valueOf(economics.dueMinor()).equals(consent.getAmountMinor())
                    || !"CNY".equals(consent.getCurrency()) || consent.getAcceptedAt() == null
                    || consent.getConfirmedAt() == null || consent.getConsumedAt() == null
                    || !"MERCHANT".equals(consent.getActorRealm())
                    || !List.of("MCH", "USR").contains(consent.getActorKind())
                    || consent.getActorNo() == null || consent.getActorNo().isBlank()
                    || consent.getAccountNo() == null || consent.getAccountNo().isBlank()) {
                throw conflict();
            }
            long gross = economics.goodsMinor();
            long discount = economics.discountMinor();
            if (discount < 0 || discount >= gross || economics.dueMinor() <= 0
                    || Math.subtractExact(gross, discount) != economics.dueMinor()
                    || economics.freightMinor() != 0 || economics.platformMinor() != 0
                    || economics.merchantMinor() != discount
                    || !CouponPort.Allocation.none().equals(economics.coupon())
                    || !Long.valueOf(gross).equals(parent.getGoodsAmount())
                    || !Long.valueOf(gross).equals(child.getGoodsAmount())
                    || !Long.valueOf(0).equals(parent.getFreightAmount())
                    || !Long.valueOf(0).equals(child.getFreightAmount())
                    || !Long.valueOf(discount).equals(parent.getDiscountAmount())
                    || !Long.valueOf(discount).equals(child.getDiscountAmount())
                    || !Long.valueOf(discount).equals(child.getDiscountMerchant())
                    || !Long.valueOf(0).equals(child.getDiscountPlatform())) {
                throw conflict();
            }
            var ids = new HashSet<Long>();
            var refs = new HashSet<String>();
            long lineGross = 0;
            long lineDue = 0;
            long itemGross = 0;
            long itemDue = 0;
            for (var source : sourceLines) {
                if (!ids.add(source.getOrderItemId()) || !refs.add(source.getClientLineRef())
                        || !op.getCaller().equals(source.getCaller())
                        || !op.getOperationNo().equals(source.getOperationNo())) {
                    throw conflict();
                }
                var priced = economics.lines().stream()
                        .filter(l -> l.clientLineRef().equals(source.getClientLineRef()))
                        .findFirst().orElseThrow(this::conflict);
                if (!priced.goodsNo().equals(source.getGoodsNo())) {
                    throw conflict();
                }
                lineGross = Math.addExact(lineGross, priced.grossMinor());
                lineDue = Math.addExact(lineDue, priced.dueMinor());
            }
            var itemIds = new HashSet<Long>();
            for (var item : originalItems) {
                if (!itemIds.add(item.getId())) {
                    throw conflict();
                }
                itemGross = Math.addExact(itemGross, item.getAmount());
                itemDue = Math.addExact(itemDue, item.getPaidShareMinor());
            }
            if (lineGross != gross || itemGross != gross || lineDue != economics.dueMinor()
                    || itemDue != economics.dueMinor() || Math.subtractExact(lineGross, lineDue) != discount
                    || Math.subtractExact(itemGross, itemDue) != discount) {
                throw conflict();
            }
        } catch (RuntimeException invalid) {
            throw conflict();
        }
    }

    /** Revalidate replayed declaration economics against the consumed original, never current prices. */
    void requireDeclaration(OrdManualServiceAdmission declaration, Facts facts) {
        requireParty(facts.op(), declaration);
        if ("MERCHANT_RECORDED_IN_PERSON".equals(facts.op().getPurchaseAuthority())
                && new CounterEconomicCodec(json).readQuote(facts.quote().getPricingJson())
                        .counterActivitySeal() != null
                && (!Objects.equals(declaration.getQuoteHash(), facts.quote().getQuoteHash())
                        || !Objects.equals(declaration.getDeclaredMinor(), facts.parent().getPayAmount())
                        || !Objects.equals(declaration.getCurrency(), facts.parent().getCurrency()))) {
            throw conflict();
        }
    }

    void requireParty(
            OrdCheckoutOperation op, OrdManualServiceAdmission declaration) {
        if (!party(op)
                        .equals(
                                new CustomerParty(
                                        declaration.getCustomerKind(),
                                        declaration.getBuyerNo(),
                                        declaration.getGuestNo(),
                                        declaration.getIdentityRevision()))
                || !op.getCaller().equals(declaration.getCaller())
                || !op.getOperationNo().equals(declaration.getOperationNo())
                || !Objects.equals(op.getOrderNo(), declaration.getOrderNo())
                || !Objects.equals(op.getSubOrderNo(), declaration.getSubOrderNo())
                || !op.getEntityNo().equals(declaration.getEntityNo())
                || !op.getStoreNo().equals(declaration.getStoreNo())) {
            throw conflict();
        }
    }

    private void alive(BaseEntity row) {
        if (!"MAIN".equals(row.getTenantNo()) || !Integer.valueOf(0).equals(row.getDeleted())) {
            throw conflict();
        }
    }

    private BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static long nz(Long value) { return value == null ? 0 : value; }
    private static int nz(Integer value) { return value == null ? 0 : value; }

    /** All root/line/unit/attempt/operation locks must already be held by the calling Core transaction. */
    ManualExecutionSource loadLocked(ExecutionCoordinates coordinates, ExecutionBinding expected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("execution source snapshot requires established owner locks");
        }
        if (!coordinates.scope().equals(expected.scope()) || !coordinates.caller().equals(expected.caller())
                || !coordinates.sourceOperationNo().equals(expected.sourceOperationNo())
                || !coordinates.orderNo().equals(expected.orderNo())
                || !coordinates.subOrderNo().equals(expected.subOrderNo())) {
            throw conflict();
        }
        var op = declarations.executionOperation(coordinates);
        var parent = declarations.lockParent(coordinates.orderNo());
        var children = declarations.lockChildren(coordinates.orderNo());
        if (children.size() != 1 || !coordinates.subOrderNo().equals(children.getFirst().getSubOrderNo())) {
            throw conflict();
        }
        var child = children.getFirst();
        var declaration = declarations.lockByOrder(coordinates.scope().entityNo(), coordinates.scope().storeNo(),
                coordinates.orderNo());
        if (op == null || declaration == null || !"ADMITTED".equals(declaration.getPhase())
                || parent == null || !"PAID".equals(parent.getStatus()) || !"FULFILLING".equals(child.getStatus())
                || !Objects.equals(parent.getVersion(), declaration.getOrderRevision())) throw conflict();
        var facts = load(op, parent, child);
        requireDeclaration(declaration, facts);
        var line = facts.lines().stream().filter(l -> expected.sourceLineNo().equals(l.getSourceLineNo()))
                .findFirst().orElseThrow(this::conflict);
        var lines = facts.lines().stream().map(l -> new ManualSourceLine(l.getSourceLineNo(), l.getClientLineRef(),
                l.getGoodsNo(), l.getSkuNo(), l.getQuantity(), l.getQuantity(), l.getUom())).toList();
        String hash = sourceHash(op, parent, child, declaration, facts.lines(), facts.items(), "ADMITTED", lines);
        if (!Objects.equals(op.getManualObservationRevision(), expected.sourceRevision())
                || !expected.sourceHash().equals(op.getManualObservationHash()) || !expected.sourceHash().equals(hash)
                || line.getQuantity() > 999 || expected.unitOrdinal() > line.getQuantity()) throw conflict();
        return new ManualExecutionSource(facts, declaration, line, expected.sourceRevision(), hash);
    }

    record ManualExecutionSource(Facts facts, OrdManualServiceAdmission admission, OrdCheckoutLine line,
            long manualRevision, String manualHash) {}

    /** Owner-internal immutable references; never exposed through an execution HTTP response. */
    record Facts(OrdCheckoutOperation op, OrdOrder parent, OrdSubOrder child, OrdCheckoutQuote quote,
            List<OrdCheckoutLine> lines, List<OrdItem> items) {
        public Facts {
            lines = List.copyOf(lines);
            items = List.copyOf(items);
        }
    }
}
