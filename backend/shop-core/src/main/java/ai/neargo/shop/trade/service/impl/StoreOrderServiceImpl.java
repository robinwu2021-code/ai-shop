package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.PosActivityPort;
import ai.neargo.shop.spi.member.PosSaleMemberPort;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.spi.invoice.InvoicePort;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.trade.entity.OrdInvoiceDataFill;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.InvoiceDataFillMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.InvoiceReadinessPolicy;
import ai.neargo.shop.trade.service.StoreOrderService;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 门店零售单的唯一实现（TDD-统一订单与开票资料 P1 / P2）。
 *
 * <p><b>P2</b>：自订行可选门店常用品项（带出品名与课税别，零税率 / 免税不需另授权）、没填课税别取门店默认课税别；
 * 本单开票时机存 {@code ord_order.invoice_timing}；{@code paidMinor} 读已挂收款（{@link StoreOrderSettlementService}）。
 *
 * <p><b>库表口径</b>：{@code customer_kind='WALK_IN'}、{@code admission_kind='STORE_SALE'}、{@code caller='POS'}
 * （V693 约束新支），{@code operation_no} 存幂等键的 SHA-256，{@code client_order_no} 门店内唯一（V692）。
 * 整单优惠落子单 {@code discount_amount}（不落负数订单行）：订单列表、退款分摊（{@link LineShares}）与发票来源
 * 都已按「商品 − 优惠 = 实付」读，负数行会让它们各自多一种情况。
 *
 * <p><b>事务</b>：建单在单号预留之外开事务（{@link DocumentNumberCoordinator#transaction} 要求），
 * 买受人与订单同一事务写入，买受人不合规整单回滚。补资料、取消锁子单行串行化。
 *
 * <p>作用域由调用方给定并显式带进每条查询，所以统一绕开数据域拦截（同一事实不判两次）。
 */
@Service
public class StoreOrderServiceImpl implements StoreOrderService {

    static final String TENANT = "MAIN";
    static final String WALK_IN = "WALK_IN";
    static final String STORE_SALE = "STORE_SALE";
    static final String CALLER = "POS";
    static final String SOURCE_TYPE = "SUB_ORDER";
    static final String SOURCE_GOODS = "GOODS";
    static final String SOURCE_ENTERED = "ENTERED";
    static final String SOURCE_FILLED = "FILLED";
    /** 自订行选了门店常用品项（P2） */
    static final String SOURCE_STORE_ITEM = "STORE_ITEM";
    /** 自订行没填课税别，取门店默认课税别（P2） */
    static final String SOURCE_STORE_DEFAULT = "STORE_DEFAULT";

    @Autowired private PosActivityPort posActivities;
    @Autowired private PosSaleMemberPort posMembers;
    @Autowired private PosActivitySaleMapper posFacts;
    @Value("${shop.trade.canonical-storage:false}") private boolean canonicalStorage;

    private final OrderMapper orders;
    private final SubOrderMapper subOrders;
    private final OrderItemMapper items;
    private final InvoiceDataFillMapper fills;
    private final GoodsQueryPort goods;
    private final GoodsPricingPolicyPort pricingPolicies;
    private final StoreOwnershipWritePort ownership;
    private final DocumentNumberCoordinator numbers;
    /** 打包排除发票模块时没有实现：准备度按「未开票、买受人未设」显示，带买受人建单回 26001 */
    private final ObjectProvider<InvoicePort> invoices;
    private final TransactionTemplate writes;
    /** 已挂收款合计（P2）：视图的 paidMinor、取消前判「有没有收过钱」 */
    private final StoreOrderSettlementService settlements;

    public StoreOrderServiceImpl(OrderMapper orders, SubOrderMapper subOrders, OrderItemMapper items,
            InvoiceDataFillMapper fills, GoodsQueryPort goods, GoodsPricingPolicyPort pricingPolicies,
            StoreOwnershipWritePort ownership,
            @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers,
            ObjectProvider<InvoicePort> invoices,
            @Qualifier(ModuleDataSources.TRADE_TX_MANAGER) PlatformTransactionManager manager,
            StoreOrderSettlementService settlements) {
        this.orders = orders;
        this.subOrders = subOrders;
        this.items = items;
        this.fills = fills;
        this.goods = goods;
        this.pricingPolicies = pricingPolicies;
        this.ownership = ownership;
        this.numbers = numbers;
        this.invoices = invoices;
        this.writes = new TransactionTemplate(manager);
        this.settlements = settlements;
    }

    // ------------------------------------------------------------------ create

    @Override
    public StoreSaleView create(Scope scope, CreateCmd cmd) {
        String keyHash = CheckoutDigests.hash(cmd.idempotencyKey());
        var prior = unscoped(() -> byClientNo(scope, cmd.clientOrderNo()));
        if (prior != null) return view(scope, prior);
        var reused = unscoped(() -> orders.selectOne(storeOrders(scope).eq("operation_no", keyHash).last("LIMIT 1")));
        if (reused != null) throw BizException.of(ErrorCode.CONFLICT);

        var plan = plan(scope, cmd);
        String orderNo;
        try {
            orderNo = unscoped(() -> numbers.transaction(
                    Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 1),
                    () -> insert(scope, cmd, keyHash, plan)));
        } catch (DuplicateKeyException race) {
            // 同一 clientOrderNo 并发双写：唯一键兜住，输的一路读回赢家那单
            var winner = unscoped(() -> byClientNo(scope, cmd.clientOrderNo()));
            if (winner == null) throw race;
            return view(scope, winner);
        }
        return get(scope, orderNo);
    }

    /** 建单前算好的订单行与金额；只读商品资料，不写库。 */
    private record Plan(List<OrdItem> lines, long goodsMinor, long discountMinor, long totalMinor) {}

    private Plan plan(Scope scope, CreateCmd cmd) {
        var skuNos = cmd.lines().stream().filter(l -> OrdItem.LINE_SKU.equals(l.kind())).map(LineCmd::skuNo)
                .distinct().toList();
        Map<String, SkuSnapshot> snapshots = skuNos.isEmpty() ? Map.of()
                : goods.snapshot(skuNos, Map.of(scope.entityNo(), scope.storeNo()));
        Map<String, TaxClass> taxClasses = skuNos.isEmpty() ? Map.of()
                : pricingPolicies.taxClasses(policyScope(scope), skuNos);
        var lines = new ArrayList<OrdItem>();
        long goodsMinor = 0;
        InvoicePort.StoreSettings settings = null;
        for (var line : cmd.lines()) {
            var item = new OrdItem();
            item.setLineKind(line.kind());
            item.setQty(line.quantity());
            item.setPrice(line.unitPriceMinor());
            item.setAmount(Math.multiplyExact(line.unitPriceMinor(), (long) line.quantity()));
            if (OrdItem.LINE_SKU.equals(line.kind())) {
                var sku = snapshots.get(line.skuNo());
                if (sku == null || !scope.entityNo().equals(sku.merchantNo())
                        || sku.storeNo() != null && !scope.storeNo().equals(sku.storeNo()))
                    throw BizException.of(ErrorCode.NOT_FOUND);
                if (sku.price() != line.unitPriceMinor() && !scope.mayReprice()) throw forbidden(BizPerms.ORDER_REPRICE);
                item.setGoodsNo(sku.goodsNo());
                item.setSkuNo(sku.skuNo());
                item.setTitle(sku.title());
                item.setCover(sku.cover());
                item.setSpec(sku.spec());
                item.setCategoryType(sku.categoryType());
                item.setCategoryNo(sku.categoryNo());
                item.setInvoiceName(sku.title());
                var taxClass = taxClasses.get(sku.skuNo());
                item.setTaxClass(InvoiceReadinessPolicy.taxType(taxClass == null ? null : taxClass.name()) == null
                        ? null : taxClass.name());
                item.setInvoiceDataSource(SOURCE_GOODS);
                if (item.getTaxClass() == null) {
                    // 商品没设课税别（或 OUT_OF_SCOPE）：取门店默认课税别（用户 2026-11-24「用门店默认税别」）；
                    // 门店也没设就留空 = 待补，不猜。默认值是店长设定的边界，零税率 / 免税不需另授权
                    if (settings == null) settings = storeSettings(scope);
                    if (settings.defaultTaxType() != null) {
                        item.setTaxClass(InvoiceReadinessPolicy.taxClass(settings.defaultTaxType()));
                        item.setInvoiceDataSource(SOURCE_STORE_DEFAULT);
                    }
                }
            } else {
                // 自订行没有商品：goods_no / sku_no 是 NOT NULL 列，存空串（不是任何商品号）
                item.setGoodsNo("");
                item.setSkuNo("");
                if (line.storeItemNo() != null) {
                    // 常用品项：品名与课税别都由门店定好，零税率 / 免税也不需另授权（PRD §五.4「或只能从常用品项里选」）
                    var preset = storeItem(scope, line.storeItemNo());
                    item.setTitle(preset.name());
                    item.setInvoiceName(preset.name());
                    item.setTaxClass(InvoiceReadinessPolicy.taxClass(preset.taxType()));
                    item.setInvoiceDataSource(SOURCE_STORE_ITEM);
                } else {
                    if (line.taxType() != null && InvoiceReadinessPolicy.reducesTax(line.taxType())
                            && !scope.mayOverrideTax()) throw forbidden(BizPerms.INVOICE_TAX_OVERRIDE);
                    item.setTitle(blankToNull(line.name()));
                    item.setInvoiceName(blankToNull(line.name()));
                    item.setTaxClass(line.taxType() == null ? null : InvoiceReadinessPolicy.taxClass(line.taxType()));
                    item.setInvoiceDataSource(item.getInvoiceName() == null && item.getTaxClass() == null
                            ? null : SOURCE_ENTERED);
                    if (item.getTaxClass() == null) {
                        if (settings == null) settings = storeSettings(scope);
                        if (settings.defaultTaxType() != null) {
                            // 门店默认课税别是店长在设定里定的边界，收银员沿用它不算「选」零税率 / 免税
                            item.setTaxClass(InvoiceReadinessPolicy.taxClass(settings.defaultTaxType()));
                            if (item.getInvoiceDataSource() == null) item.setInvoiceDataSource(SOURCE_STORE_DEFAULT);
                        }
                    }
                }
            }
            goodsMinor = Math.addExact(goodsMinor, item.getAmount());
            lines.add(item);
        }
        long discountMinor = 0;
        for (var adjustment : cmd.adjustments()) discountMinor = Math.subtractExact(discountMinor, adjustment.amountMinor());
        long total = goodsMinor - discountMinor;
        if (total < 0) throw BizException.of(ErrorCode.STORE_ORDER_TOTAL_INVALID);
        long[] shares = LineShares.allocate(total, lines.stream().mapToLong(OrdItem::getAmount).toArray());
        for (int i = 0; i < lines.size(); i++) lines.get(i).setPaidShareMinor(shares[i]);
        return new Plan(lines, goodsMinor, discountMinor, total);
    }

    private String insert(Scope scope, CreateCmd cmd, String keyHash, Plan plan) {
        ownership.lockAndCheck(TENANT, scope.entityNo(), scope.storeNo());
        var orderNo = numbers.takeReserved(DocumentType.TRADE_ORDER);
        var subNo = numbers.takeReserved(DocumentType.TRADE_SUBORDER);
        var order = new OrdOrder();
        order.setTenantNo(TENANT);
        order.setOrderNo(orderNo.value());
        order.setCustomerKind(WALK_IN);
        order.setIdentityRevision(null);
        order.setAdmissionKind(STORE_SALE);
        order.setCaller(CALLER);
        order.setEntityNo(scope.entityNo());
        order.setStoreNo(scope.storeNo());
        order.setOperationNo(keyHash);
        order.setClientOrderNo(cmd.clientOrderNo());
        String memberNo = cmd.activityFactsVersion() == null ? null : cmd.memberNo();
        String memberUser = memberNo == null ? null
                : posMembers.requireUser(TENANT, scope.entityNo(), scope.storeNo(), memberNo);
        if (canonicalStorage) order.setUserNo(memberUser);
        order.setChannel(cmd.channel());
        order.setInvoiceTiming(cmd.invoiceTiming());
        order.setCurrency(cmd.currency());
        order.setGoodsAmount(plan.goodsMinor());
        order.setFreightAmount(0L);
        order.setDiscountAmount(plan.discountMinor());
        order.setPayAmount(plan.totalMinor());
        order.setStatus(OrdOrder.WAIT_PAY);
        order.setPointsIntegrated(false);
        orders.insert(order);
        if (!canonicalStorage && cmd.activityFactsVersion() != null) {
            posFacts.setLegacyFacts(TENANT, orderNo.value(), cmd.activityFactsVersion(), memberNo);
        }
        var sub = new OrdSubOrder();
        sub.setTenantNo(TENANT);
        sub.setSubOrderNo(subNo.value());
        sub.setOrderNo(orderNo.value());
        sub.setCustomerKind(WALK_IN);
        sub.setIdentityRevision(null);
        sub.setAdmissionKind(STORE_SALE);
        sub.setCaller(CALLER);
        sub.setEntityNo(scope.entityNo());
        sub.setStoreNo(scope.storeNo());
        sub.setTrafficSource(OrdSubOrder.TRAFFIC_MERCHANT_OWNED);
        sub.setGoodsAmount(plan.goodsMinor());
        sub.setFreightAmount(0L);
        sub.setDiscountAmount(plan.discountMinor());
        sub.setDiscountMerchant(plan.discountMinor());
        sub.setDiscountPlatform(0L);
        sub.setPayAmount(plan.totalMinor());
        sub.setStatus(OrdSubOrder.WAIT_PAY);
        sub.setRefundedMinor(0L);
        sub.setRefundProgress("NONE");
        sub.setRequireBuyerConfirm(0);
        subOrders.insert(sub);
        for (var item : plan.lines()) {
            item.setTenantNo(TENANT);
            item.setOrderNo(orderNo.value());
            item.setSubOrderNo(subNo.value());
            item.setSupplyStoreNo(scope.storeNo());
            item.setRefundedQty(0);
            item.setRefundedMinor(0L);
            item.setIsGift(false);
            item.setWeighed(false);
            item.setWeighAdjustMinor(0L);
            items.insert(item);
        }
        var selected = cmd.adjustments().stream().filter(a -> "ACTIVITY".equals(a.kind()))
                .sorted(java.util.Comparator.comparing(AdjustmentCmd::activityNo)).toList();
        if (!selected.isEmpty() && cmd.activityFactsVersion() == null
                || selected.stream().map(AdjustmentCmd::activityNo).distinct().count() != selected.size())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var cart = plan.lines().stream().map(l -> new PosActivityPort.Line(l.getGoodsNo(), l.getCategoryNo(),
                l.getQty(), l.getPrice())).toList();
        long[] activityTotals = new long[cart.size()];
        var itemOffers = selected.size()<2 ? java.util.Set.<String>of() :
                posActivities.itemOffers(TENANT, selected.stream().map(AdjustmentCmd::activityNo).toList());
        var executionOrder=selected.stream().sorted(java.util.Comparator.comparing(a -> !itemOffers.contains(a.activityNo()))).toList();
        boolean hasOrderOffer = false;
        var canonicalShares = new ArrayList<PosSaleQuote.Share>();
        for (int step = 0; step < executionOrder.size(); step++) {
            var adjustment = executionOrder.get(step);
            long amount = Math.negateExact(adjustment.amountMinor());
            var eligible = new ArrayList<>(cart);
            if (!itemOffers.contains(adjustment.activityNo())) {
                for (int i=0;i<eligible.size();i++) if (activityTotals[i]>0)
                    eligible.set(i,new PosActivityPort.Line("",null,0,0));
            }
            var use = posActivities.apply(TENANT, scope.entityNo(), scope.storeNo(), orderNo.value(), subNo.value(),
                    memberUser, cmd.currency(), adjustment.activityNo(), amount, eligible);
            if (use.orderOffer() && hasOrderOffer) throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
            hasOrderOffer |= use.orderOffer();
            var shares = LineShares.allocate(amount, use.weights());
            for (int i=0; i<shares.length; i++) {
                if (shares[i] == 0) continue;
                var item = plan.lines().get(i);
                if (activityTotals[i] != 0) throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
                activityTotals[i] = shares[i];
                if (activityTotals[i] > item.getAmount()) throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
                if (canonicalStorage) {
                    canonicalShares.add(new PosSaleQuote.Share(i, step, use.applyNo(), adjustment.activityNo(),
                            use.versionNo(), use.weights()[i], shares[i]));
                } else {
                    posFacts.insert(TENANT, subNo.value(), item.getId(), use.applyNo(), adjustment.activityNo(),
                            use.versionNo(), shares[i]);
                }
            }
        }
        if (!selected.isEmpty()) {
            long[] remaining = new long[cart.size()];
            for (int i=0;i<remaining.length;i++) remaining[i]=plan.lines().get(i).getAmount()-activityTotals[i];
            long[] paid = LineShares.allocate(plan.totalMinor(), remaining);
            for (int i = 0; i < paid.length; i++) {
                var item = plan.lines().get(i);
                item.setPaidShareMinor(paid[i]);
                items.update(null, new UpdateWrapper<OrdItem>().eq("id", item.getId()).set("paid_share_minor", paid[i]));
            }
        }
        if (canonicalStorage && cmd.activityFactsVersion() != null) {
            PosSaleQuote.record(posFacts, order, subNo.value(), memberNo, cmd.activityFactsVersion(), plan.lines(),
                    canonicalShares, activityTotals);
        }
        numbers.bindReserved(new DocumentSource(TENANT, "CORE", "TRADE_ORDER", orderNo.value()), orderNo);
        numbers.bindReserved(new DocumentSource(TENANT, "CORE", "TRADE_SUBORDER", subNo.value()), subNo);
        if (cmd.buyer() != null) requireInvoices().rememberBuyer(invoiceScope(scope), SOURCE_TYPE, subNo.value(),
                buyer(cmd.buyer()));
        return orderNo.value();
    }

    // ------------------------------------------------------------------ read

    @Override
    public StoreSaleView get(Scope scope, String orderNo) {
        return view(scope, unscoped(() -> requireOrder(scope, orderNo)));
    }

    private StoreSaleView view(Scope scope, OrdOrder order) {
        var sub = unscoped(() -> subOrders.selectOne(new QueryWrapper<OrdSubOrder>().eq("tenant_no", TENANT)
                .eq("order_no", order.getOrderNo()).last("LIMIT 1")));
        var lines = unscoped(() -> linesOf(sub.getSubOrderNo()));
        var missing = InvoiceReadinessPolicy.missing(lines);
        var port = invoices.getIfAvailable();
        var invoiceScope = invoiceScope(scope);
        String invoiceNo = port == null ? null : port.activeOf(invoiceScope, SOURCE_TYPE, sub.getSubOrderNo())
                .map(InvoicePort.InvoiceReceipt::invoiceNo).orElse(null);
        boolean buyerSet = port != null
                && port.rememberedBuyer(invoiceScope, SOURCE_TYPE, sub.getSubOrderNo()).isPresent();
        var lineViews = new ArrayList<LineView>();
        for (int i = 0; i < lines.size(); i++) {
            var l = lines.get(i);
            String taxType = InvoiceReadinessPolicy.taxType(l.getTaxClass());
            lineViews.add(new LineView(i + 1, l.getLineKind(), blankToNull(l.getSkuNo()), l.getInvoiceName(), taxType,
                    l.getQty(), l.getPrice(), l.getAmount(), l.getInvoiceName() != null && taxType != null));
        }
        var readiness = new InvoiceReadinessView(InvoiceReadinessPolicy.state(sub, invoiceNo != null, missing),
                missing.stream().map(m -> new MissingView(m.lineNo(), m.fields())).toList(), buyerSet, invoiceNo);
        long paidMinor = settlements.paidMinor(order.getEntityNo(), order.getStoreNo(), sub.getSubOrderNo(),
                order.getCurrency());
        return new StoreSaleView(order.getOrderNo(), sub.getSubOrderNo(), order.getClientOrderNo(), status(sub),
                order.getCurrency(), order.getPayAmount(), paidMinor, order.getInvoiceTiming(), lineViews, readiness);
    }

    private static String status(OrdSubOrder sub) {
        if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) return "AWAITING_PAYMENT";
        if (OrdSubOrder.PAID.contains(sub.getStatus())) return "PAID";
        return sub.getStatus(); // CANCELLED / REFUNDED
    }

    // ------------------------------------------------------------------ fill

    @Override
    public StoreSaleView fillInvoiceData(Scope scope, String orderNo, FillCmd cmd) {
        String keyHash = CheckoutDigests.hash(cmd.idempotencyKey());
        var order = unscoped(() -> requireOrder(scope, orderNo));
        unscoped(() -> writes.execute(tx -> {
            var sub = lockSub(order);
            boolean replay = fills.selectCount(new QueryWrapper<OrdInvoiceDataFill>().eq("tenant_no", TENANT)
                    .eq("sub_order_no", sub.getSubOrderNo()).eq("request_key_hash", keyHash)) > 0;
            if (replay) return null;
            var port = invoices.getIfAvailable();
            var invoiceScope = invoiceScope(scope);
            if (port != null && port.activeOf(invoiceScope, SOURCE_TYPE, sub.getSubOrderNo()).isPresent())
                throw BizException.of(ErrorCode.INVOICE_DATA_LOCKED);
            var lines = linesOf(sub.getSubOrderNo());
            for (var fill : cmd.lines()) {
                if (fill.lineNo() < 1 || fill.lineNo() > lines.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
                var item = lines.get(fill.lineNo() - 1);
                String name = fill.name();
                String taxType = fill.taxType();
                boolean preset = fill.storeItemNo() != null;
                if (preset) {
                    var storeItem = storeItem(scope, fill.storeItemNo());
                    name = storeItem.name();
                    taxType = storeItem.taxType();
                }
                if (name != null && fillField(scope, sub, item, fill.lineNo(), OrdInvoiceDataFill.NAME,
                        item.getInvoiceName(), name, cmd, keyHash))
                    items.update(null, new UpdateWrapper<OrdItem>().eq("id", item.getId()).isNull("invoice_name")
                            .set("invoice_name", name).set("title", name)
                            .set("invoice_data_source", SOURCE_FILLED));
                if (taxType != null) {
                    String taxClass = InvoiceReadinessPolicy.taxClass(taxType);
                    String current = InvoiceReadinessPolicy.taxType(item.getTaxClass()) == null ? null
                            : item.getTaxClass();
                    if (current == null && !preset && InvoiceReadinessPolicy.reducesTax(taxType)
                            && !scope.mayOverrideTax())
                        throw forbidden(BizPerms.INVOICE_TAX_OVERRIDE);
                    if (fillField(scope, sub, item, fill.lineNo(), OrdInvoiceDataFill.TAX_CLASS, current, taxClass,
                            cmd, keyHash))
                        items.update(null, new UpdateWrapper<OrdItem>().eq("id", item.getId())
                                .set("tax_class", taxClass).set("invoice_data_source", SOURCE_FILLED));
                }
            }
            if (cmd.buyer() != null) {
                var invoicePort = requireInvoices();
                if (invoicePort.rememberedBuyer(invoiceScope, SOURCE_TYPE, sub.getSubOrderNo()).isPresent())
                    throw BizException.of(ErrorCode.INVOICE_DATA_FROZEN);
                invoicePort.rememberBuyer(invoiceScope, SOURCE_TYPE, sub.getSubOrderNo(), buyer(cmd.buyer()));
                audit(scope, sub, 0, OrdInvoiceDataFill.BUYER, null, cmd.buyer().type(), cmd, keyHash);
            }
            return null;
        }));
        return get(scope, orderNo);
    }

    /**
     * 补一个字段：原值为空 → 记稽核并返回 true（调用方写回订单行）；原值等于新值 → 什么都不做；
     * 原值不同 → 已冻结。
     */
    private boolean fillField(Scope scope, OrdSubOrder sub, OrdItem item, int lineNo, String field, String current,
            String value, FillCmd cmd, String keyHash) {
        if (current != null) {
            if (current.equals(value)) return false;
            throw BizException.of(ErrorCode.INVOICE_DATA_FROZEN);
        }
        audit(scope, sub, lineNo, field, null, value, cmd, keyHash);
        return true;
    }

    private void audit(Scope scope, OrdSubOrder sub, int lineNo, String field, String oldValue, String newValue,
            FillCmd cmd, String keyHash) {
        var row = new OrdInvoiceDataFill();
        row.setTenantNo(TENANT);
        row.setFillNo(BizKey.next("IDF"));
        row.setOrderNo(sub.getOrderNo());
        row.setSubOrderNo(sub.getSubOrderNo());
        row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo());
        row.setLineNo(lineNo);
        row.setFieldCode(field);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setReason(cmd.reason());
        row.setOperatorNo(scope.operatorNo());
        row.setRequestKeyHash(keyHash);
        // 唯一键 (sub_order_no, line_no, field_code)：同一空字段被两个请求同时补，后到的这里撞键回滚
        fills.insert(row);
    }

    // ------------------------------------------------------------------ cancel

    @Override
    public StoreSaleView cancel(Scope scope, String orderNo, String reason) {
        var order = unscoped(() -> requireOrder(scope, orderNo));
        unscoped(() -> writes.execute(tx -> {
            var sub = lockSub(order);
            if (OrdSubOrder.CANCELLED.equals(sub.getStatus())) return null;
            if (!OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
            // 部分付款的单不能直接取消：钱已经挂在单上，先作废现金记录 / 退收款
            if (settlements.paidMinor(order.getEntityNo(), order.getStoreNo(), sub.getSubOrderNo(),
                    order.getCurrency()) > 0) throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
            int changed = orders.update(null, new UpdateWrapper<OrdOrder>().eq("tenant_no", TENANT)
                    .eq("order_no", order.getOrderNo()).eq("status", OrdOrder.WAIT_PAY)
                    .set("status", OrdOrder.CANCELLED).set("cancel_reason", reason).setSql("version=version+1"));
            if (changed != 1) throw BizException.of(ErrorCode.STORE_ORDER_STATE_INVALID);
            subOrders.update(null, new UpdateWrapper<OrdSubOrder>().eq("id", sub.getId())
                    .eq("status", OrdSubOrder.WAIT_PAY).set("status", OrdSubOrder.CANCELLED)
                    .setSql("version=version+1"));
            var facts = canonicalStorage ? posFacts.canonicalFacts(TENANT, sub.getSubOrderNo(), orderNo)
                    : posFacts.legacyFacts(TENANT, orderNo);
            if (facts != null) {
                posActivities.cancel(orderNo);
                if (canonicalStorage) posFacts.decideAllocations(TENANT, sub.getSubOrderNo(), orderNo, "CANCELLED");
            }
            return null;
        }));
        return get(scope, orderNo);
    }

    // ------------------------------------------------------------------ helpers

    private QueryWrapper<OrdOrder> storeOrders(Scope scope) {
        return new QueryWrapper<OrdOrder>().eq("tenant_no", TENANT).eq("entity_no", scope.entityNo())
                .eq("store_no", scope.storeNo()).eq("admission_kind", STORE_SALE);
    }

    private OrdOrder byClientNo(Scope scope, String clientOrderNo) {
        return orders.selectOne(storeOrders(scope).eq("client_order_no", clientOrderNo));
    }

    private OrdOrder requireOrder(Scope scope, String orderNo) {
        var order = orders.selectOne(storeOrders(scope).eq("order_no", orderNo));
        if (order == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return order;
    }

    private OrdSubOrder lockSub(OrdOrder order) {
        return subOrders.selectOne(new QueryWrapper<OrdSubOrder>().eq("tenant_no", TENANT)
                .eq("order_no", order.getOrderNo()).last("LIMIT 1 FOR UPDATE"));
    }

    private List<OrdItem> linesOf(String subOrderNo) {
        return items.selectList(new QueryWrapper<OrdItem>().eq("tenant_no", TENANT).eq("sub_order_no", subOrderNo)
                .orderByAsc("id"));
    }

    /** 本店启用中的常用品项；不存在、别店、已停用或没有发票模块都回 20026。 */
    private InvoicePort.StoreItem storeItem(Scope scope, String storeItemNo) {
        var port = invoices.getIfAvailable();
        return (port == null ? Optional.<InvoicePort.StoreItem>empty()
                : port.storeItem(invoiceScope(scope), storeItemNo)).filter(InvoicePort.StoreItem::enabled)
                .orElseThrow(() -> BizException.of(ErrorCode.STORE_ORDER_STORE_ITEM_UNAVAILABLE));
    }

    /** 门店开票设定；没有发票模块时按「无默认税别、手动开」。 */
    private InvoicePort.StoreSettings storeSettings(Scope scope) {
        var port = invoices.getIfAvailable();
        return port == null ? new InvoicePort.StoreSettings(null, InvoicePort.StoreSettings.MANUAL)
                : port.storeSettings(invoiceScope(scope));
    }

    private InvoicePort requireInvoices() {
        var port = invoices.getIfAvailable();
        if (port == null) throw BizException.of(ErrorCode.INVOICE_REGIME_NOT_ENABLED);
        return port;
    }

    private static InvoicePort.Scope invoiceScope(Scope scope) {
        return new InvoicePort.Scope(TENANT, scope.entityNo(), scope.storeNo(), scope.operatorNo());
    }

    private static GoodsPricingPolicyPort.Scope policyScope(Scope scope) {
        return new GoodsPricingPolicyPort.Scope(TENANT, scope.entityNo(), scope.storeNo());
    }

    /** 四种买受人只取各自的字段，其余一律不带 —— 不让「打统编」顺手捎上一个载具号 */
    static InvoicePort.Buyer buyer(BuyerCmd b) {
        return switch (b.type()) {
            case "COMPANY" -> new InvoicePort.Buyer(b.taxId(), b.name(), null, null, null);
            case "CARRIER" -> new InvoicePort.Buyer(null, null, b.carrierType(), b.carrierId(), null);
            case "DONATE" -> new InvoicePort.Buyer(null, null, null, null, b.donateCode());
            default -> InvoicePort.Buyer.consumer();
        };
    }

    private static BizException forbidden(String perm) {
        return BizException.of(ErrorCode.BIZ_ROLE_FORBIDDEN, BizPerms.labelOf(perm));
    }

    private static <T> T unscoped(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action::get);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
