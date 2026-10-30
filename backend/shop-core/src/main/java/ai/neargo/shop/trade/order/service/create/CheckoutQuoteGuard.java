package ai.neargo.shop.trade.order.service.create;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlan.PreparedCommand;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.CheckoutMappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/** The ordinary writer seals its actual calculation against locked durable consumer consent. */
@Component
public class CheckoutQuoteGuard {
    private final CheckoutMappers.OperationMapper operations;
    private final CheckoutMappers.QuoteMapper quotes;
    private final CheckoutMappers.ConfirmationMapper confirmations;
    private final CheckoutMappers.LineMapper lines;
    private final StoreSalesCatalogPort catalogs;
    private final CampaignPort campaigns;
    private final CouponPort coupons;
    private final ObjectMapper json;
    private final CheckoutClock clock;
    private final DataSource source;

    /** Wire the original owner mappers and local capabilities to the same platform transaction. */
    public CheckoutQuoteGuard(
            CheckoutMappers.OperationMapper operations,
            CheckoutMappers.QuoteMapper quotes,
            CheckoutMappers.ConfirmationMapper confirmations,
            CheckoutMappers.LineMapper lines,
            StoreSalesCatalogPort catalogs,
            CampaignPort campaigns,
            CouponPort coupons,
            ObjectMapper json,
            CheckoutClock clock,
            @Qualifier("dataSource") DataSource source) {
        this.operations = operations;
        this.quotes = quotes;
        this.confirmations = confirmations;
        this.lines = lines;
        this.catalogs = catalogs;
        this.campaigns = campaigns;
        this.coupons = coupons;
        this.json = json;
        this.clock = clock;
        this.source = source;
    }

    /**
     * Lock the original quote, coupon and selected activities before refreshing catalog time facts.
     */
    public CheckoutPricingScope context(ExpectedCheckoutQuote expected) {
        var locked = locked(expected);
        var frozen = json.readValue(locked.operation().getCommandJson(), CheckoutCommand.class);
        var original =
                json.readValue(locked.quote().getPricingJson(), CheckoutEconomicSnapshot.class);
        var op = locked.operation();
        catalogs.lockForCheckout(op.getBuyerNo(), op.getEntityNo(), op.getStoreNo(), clock.now());
        coupons.lockOrdinary(op.getBuyerNo(), frozen.command().couponNo());
        campaigns.requireOrdinarySelection(op.getBuyerNo(), original.activity());
        // The last owner lock may have crossed a future catalog version's effective time.
        // This reentrant head read does not recompute prices or release any owner lock.
        var catalog =
                catalogs.lockForCheckout(
                        op.getBuyerNo(), op.getEntityNo(), op.getStoreNo(), clock.now());
        requireFresh(expected, locked);
        return new CheckoutPricingScope(
                "MAIN",
                op.getEntityNo(),
                op.getStoreNo(),
                op.getScopeRevision(),
                op.getScopeHash(),
                frozen.lines(),
                catalog,
                op.getAdmissionKind(),
                op.getOperationNo());
    }

    /**
     * Initial manual pricing is possible only after its dedicated operation is actually persisted.
     */
    public void requireManualPricing(CheckoutPricingScope scope, String buyer) {
        transaction();
        var op = operations.lock("BEAUTY", scope.operationNo());
        if (op == null
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !"CONSUMER_SESSION".equals(op.getPurchaseAuthority())
                || !"USER".equals(op.getCustomerKind())
                || !buyer.equals(op.getBuyerNo())
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())
                || !scope.scopeHash().equals(op.getScopeHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** An initial counter quote is priced only after its immutable typed operation is inserted. */
    public void requireCounterPricing(CheckoutPricingScope scope) {
        transaction();
        var op = operations.lock("BEAUTY", scope.operationNo());
        if (op == null
                || !scope.counter()
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !scope.purchaseAuthority().equals(op.getPurchaseAuthority())
                || !scope.party().equals(party(op))
                || !scope.entityNo().equals(op.getEntityNo())
                || !scope.storeNo().equals(op.getStoreNo())
                || !scope.scopeHash().equals(op.getScopeHash())
                || scope.scopeRevision() != op.getScopeRevision()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    public CheckoutPricingScope counterContext(CounterPurchaseAuthorization authorization) {
        var locked = counterLocked(authorization);
        var op = locked.operation();
        var frozen = json.readValue(op.getCommandJson(), PreparedCommand.class);
        var codec = new CounterEconomicCodec(json);
        var snapshot = codec.readQuoteSnapshot(locked.quote().getPricingJson());
        var original = snapshot.economic();
        codec.requireQuoteHash(authorization.party(), frozen.lines(), original, locked.quote().getQuoteHash());
        var policy = snapshot.schema() == 0 ? CounterEconomicCodec.CounterPolicy.FROZEN_M2A_ZERO
                : CounterEconomicCodec.CounterPolicy.CURRENT_FULL_CUT;
        if (snapshot.schema() == 0) {
            if (!codec.legacyZero(original, frozen.command())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        } else {
            var seal = original.counterActivitySeal();
            campaigns.requireCounterSelection(authorization.party(), seal.groups(), frozen.command().activityChoices(),
                    seal, original.activity());
        }
        var catalog =
                catalogs.lockForCounterCheckout(
                        "MAIN", op.getEntityNo(), op.getStoreNo(), clock.now());
        requireFresh(authorization.expected(), locked);
        return new CheckoutPricingScope(
                "MAIN",
                op.getEntityNo(),
                op.getStoreNo(),
                op.getScopeRevision(),
                op.getScopeHash(),
                frozen.lines(),
                catalog,
                "MANUAL_OFFLINE",
                op.getOperationNo(),
                authorization.party(),
                "MERCHANT_RECORDED_IN_PERSON",
                policy,
                original);
    }

    public void requireCounterMatches(
            CounterPurchaseAuthorization authorization, CheckoutEconomicSnapshot actual) {
        var locked = counterLocked(authorization);
        requireFresh(authorization.expected(), locked);
        var op = locked.operation();
        var codec = new CounterEconomicCodec(json);
        var original = codec.readQuote(locked.quote().getPricingJson());
        var frozen = json.readValue(op.getCommandJson(), PreparedCommand.class);
        codec.requireQuoteHash(authorization.party(), frozen.lines(), original, locked.quote().getQuoteHash());
        if (original.counterActivitySeal() != null) {
            var seal = original.counterActivitySeal();
            // Re-evaluate only after the actual base/catalog/pricing waits have all completed.
            campaigns.requireCounterSelection(authorization.party(), seal.groups(), frozen.command().activityChoices(),
                    seal, original.activity());
        } else if (!codec.legacyZero(original, frozen.command())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (!Objects.equals(original, actual)) {
            throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
        }

        if (actual == null
                || !authorization.expected().quoteHash().equals(actual.quoteHash())
                || !Objects.equals(authorization.party().userNo(), actual.buyerNo())
                || !op.getEntityNo().equals(actual.entityNo())
                || !op.getStoreNo().equals(actual.storeNo())
                || !"CNY".equals(actual.currency())
                || !"OFFLINE".equals(actual.payMode())
                || !Long.valueOf(actual.dueMinor()).equals(locked.confirmation().getAmountMinor())
                || !"STORE_VERIFY".equals(actual.fulfillment())
                || !OrdSubOrder.SERVICE_EXECUTION.equals(actual.fulfillmentAuthority())) {
            throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
        }
    }

    public void completeCounter(
            CounterPurchaseAuthorization authorization, String orderNo, String subOrderNo) {
        completeLocked(counterLocked(authorization), orderNo, subOrderNo);
    }

    private Locked counterLocked(CounterPurchaseAuthorization authorization) {
        transaction();
        var user = SecurityUtils.requireUser();
        if (authorization == null
                || user.realm() != Realm.MERCHANT
                || !"MAIN".equals(user.tenantNo())
                || !(user.subjectKind() == SubjectKind.MCH
                        || user.subjectKind() == SubjectKind.USR)) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        var expected = authorization.expected();
        var op = operations.lock("BEAUTY", expected.operationNo());
        var quote = op == null ? null : quotes.lock(op.getCurrentQuoteNo());
        var confirmation =
                op == null || op.getConfirmationNo() == null
                        ? null
                        : confirmations.lock(op.getConfirmationNo());
        if (op == null
                || quote == null
                || confirmation == null
                || !"MERCHANT_RECORDED_IN_PERSON".equals(op.getPurchaseAuthority())
                || !op.getPurchaseAuthority().equals(confirmation.getPurchaseAuthority())
                || !"MANUAL_OFFLINE".equals(op.getAdmissionKind())
                || !op.getAdmissionKind().equals(confirmation.getAdmissionKind())
                || !op.getEntityNo().equals(confirmation.getEntityNo())
                || !op.getStoreNo().equals(confirmation.getStoreNo())
                || !authorization.party().equals(party(op))
                || !op.getCustomerKind().equals(confirmation.getCustomerKind())
                || !Objects.equals(op.getBuyerNo(), confirmation.getBuyerNo())
                || !Objects.equals(op.getGuestNo(), confirmation.getGuestNo())
                || !Objects.equals(op.getIdentityRevision(), confirmation.getIdentityRevision())
                || !authorization.authorityNamespaceHash().equals(op.getAuthorityNamespaceHash())
                || !authorization
                        .authorityNamespaceHash()
                        .equals(confirmation.getAuthorityNamespaceHash())
                || !authorization.actorNo().equals(confirmation.getActorNo())
                || !authorization.actorRealm().equals(confirmation.getActorRealm())
                || !authorization.actorKind().equals(confirmation.getActorKind())
                || !authorization.accountNo().equals(confirmation.getAccountNo())
                || confirmation.getAcceptedAt() == null
                || op.getPlaceKeyHash() == null
                || !"CONFIRMED".equals(op.getPhase())
                || !"CONFIRMED".equals(quote.getStatus())
                || !"AUTHORIZED".equals(confirmation.getStatus())
                || !expected.quoteNo().equals(op.getCurrentQuoteNo())
                || !expected.confirmationNo().equals(op.getConfirmationNo())
                || !expected.quoteNo().equals(confirmation.getQuoteNo())
                || !expected.operationNo().equals(quote.getOperationNo())
                || !expected.operationNo().equals(confirmation.getOperationNo())
                || !"BEAUTY".equals(quote.getCaller())
                || !"BEAUTY".equals(confirmation.getCaller())
                || !expected.quoteHash().equals(quote.getQuoteHash())
                || !expected.quoteHash().equals(confirmation.getQuoteHash())
                || expected.quoteRevision() != quote.getRevision()
                || expected.quoteRevision() != confirmation.getQuoteRevision()
                || !expected.scopeHash().equals(op.getScopeHash())
                || !"MANUAL_OFFLINE".equals(expected.admissionKind())
                || !"ORDINARY_SERVICE_PURCHASE".equals(confirmation.getPurpose())) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        return new Locked(op, quote, confirmation);
    }

    private static CustomerParty party(OrdCheckoutOperation op) {
        return new CustomerParty(
                op.getCustomerKind(), op.getBuyerNo(), op.getGuestNo(), op.getIdentityRevision());
    }

    /**
     * No Ports, network reads, or alternative pricing calculations are allowed at this seal point.
     */
    public void requireMatches(ExpectedCheckoutQuote expected, CheckoutEconomicSnapshot actual) {
        var locked = locked(expected);
        requireFresh(expected, locked);
        var op = locked.operation();
        if (actual == null
                || !expected.quoteHash().equals(actual.quoteHash())
                || !op.getBuyerNo().equals(actual.buyerNo())
                || !op.getEntityNo().equals(actual.entityNo())
                || !op.getStoreNo().equals(actual.storeNo())
                || !"CNY".equals(actual.currency())
                || !Long.valueOf(actual.dueMinor()).equals(locked.confirmation().getAmountMinor())
                || !"STORE_VERIFY".equals(actual.fulfillment())
                || !OrdSubOrder.SERVICE_EXECUTION.equals(actual.fulfillmentAuthority())) {
            throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
        }
    }

    /**
     * Persist stable source identity only for the item and paid share validated at the economic
     * seal.
     */
    public void recordLine(ExpectedCheckoutQuote expected, OrdItem item, QuoteLine economic) {
        transaction();
        if (item.getId() == null
                || !Objects.equals(item.getGoodsNo(), economic.goodsNo())
                || !Objects.equals(item.getSkuNo(), economic.skuNo())
                || !Integer.valueOf(economic.quantity()).equals(item.getQty())
                || !Long.valueOf(economic.unitPriceMinor()).equals(item.getPrice())
                || !Long.valueOf(economic.grossMinor()).equals(item.getAmount())
                || !Long.valueOf(economic.dueMinor()).equals(item.getPaidShareMinor())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var row = new OrdCheckoutLine();
        row.setTenantNo("MAIN");
        row.setCaller("BEAUTY");
        row.setOperationNo(expected.operationNo());
        row.setSourceLineNo("CL" + UUID.randomUUID().toString().replace("-", ""));
        row.setClientLineRef(economic.clientLineRef());
        row.setSubOrderNo(item.getSubOrderNo());
        row.setOrderItemId(item.getId());
        row.setGoodsNo(item.getGoodsNo());
        row.setSkuNo(item.getSkuNo());
        row.setQuantity(item.getQty());
        row.setUom("COUNT");
        row.setVersion(1L);
        row.setDeleted(0);
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(SecurityUtils.currentUserNo());
        row.setUpdatedBy(SecurityUtils.currentUserNo());
        lines.insert(row);
    }

    /**
     * Commit confirmation consumption and operation success in the same transaction as the order.
     */
    public void complete(ExpectedCheckoutQuote expected, String orderNo, String subOrderNo) {
        completeLocked(locked(expected), orderNo, subOrderNo);
    }

    private void completeLocked(Locked locked, String orderNo, String subOrderNo) {
        var confirmation = locked.confirmation();
        confirmation.setStatus("CONSUMED");
        confirmation.setOrderNo(orderNo);
        confirmation.setConsumedAt(LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC));
        changed(confirmations.updateById(confirmation));
        var quote = locked.quote();
        quote.setStatus("CONSUMED");
        changed(quotes.updateById(quote));
        var op = locked.operation();
        op.setPhase("CREATED");
        op.setOrderNo(orderNo);
        op.setSubOrderNo(subOrderNo);
        changed(operations.updateById(op));
    }

    private Locked locked(ExpectedCheckoutQuote expected) {
        transaction();
        if (expected == null) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER
                || user.subjectKind() != SubjectKind.USR
                || !"MAIN".equals(user.tenantNo())
                || SecurityUtils.currentToken().isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var op = operations.lock("BEAUTY", expected.operationNo());
        var quote = op == null ? null : quotes.lock(op.getCurrentQuoteNo());
        var confirmation =
                op == null || op.getConfirmationNo() == null
                        ? null
                        : confirmations.lock(op.getConfirmationNo());
        if (op == null
                || quote == null
                || confirmation == null
                || !"CONSUMER_SESSION".equals(op.getPurchaseAuthority())
                || !"CONSUMER_SESSION".equals(confirmation.getPurchaseAuthority())
                || !"USER".equals(op.getCustomerKind())
                || !"USER".equals(confirmation.getCustomerKind())
                || !Objects.equals(expected.admissionKind(), op.getAdmissionKind())
                || !"CONFIRMED".equals(op.getPhase())
                || !"CONFIRMED".equals(quote.getStatus())
                || !"AUTHORIZED".equals(confirmation.getStatus())
                || op.getPlaceKeyHash() == null
                || !user.userNo().equals(op.getBuyerNo())
                || !user.userNo().equals(confirmation.getBuyerNo())
                || !expected.quoteNo().equals(op.getCurrentQuoteNo())
                || !expected.quoteNo().equals(confirmation.getQuoteNo())
                || !expected.confirmationNo().equals(op.getConfirmationNo())
                || !expected.operationNo().equals(quote.getOperationNo())
                || !expected.operationNo().equals(confirmation.getOperationNo())
                || !expected.quoteHash().equals(quote.getQuoteHash())
                || !expected.quoteHash().equals(confirmation.getQuoteHash())
                || expected.quoteRevision() != quote.getRevision()
                || expected.quoteRevision() != confirmation.getQuoteRevision()
                || !expected.scopeHash().equals(op.getScopeHash())
                || !"ORDINARY_SERVICE_PURCHASE".equals(confirmation.getPurpose())) {
            throw BizException.of(ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        }
        return new Locked(op, quote, confirmation);
    }

    private void requireFresh(ExpectedCheckoutQuote expected, Locked locked) {
        var now = clock.now();
        if (expected.expiresAt() == null
                || !now.isBefore(expected.expiresAt())
                || !now.isBefore(locked.quote().getExpiresAt().toInstant(ZoneOffset.UTC))
                || !now.isBefore(locked.confirmation().getExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw BizException.of(ErrorCode.CHECKOUT_QUOTE_STALE);
        }
    }

    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void changed(int affected) {
        if (affected != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private record Locked(
            OrdCheckoutOperation operation,
            OrdCheckoutQuote quote,
            OrdCheckoutConfirmation confirmation) {}
}
