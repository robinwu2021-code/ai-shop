package ai.neargo.shop.food.assembler;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.Line;
import ai.neargo.shop.spi.printing.PrintPort.Scope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit projection excludes personal data and payment claims; printing never serializes a source
 * row.
 */
public class FoodPrintDocumentAssembler {
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final CoreLineAllocationMapper allocations;
    private final ReturnMapper returns;
    private final ReturnItemMapper returnItems;
    private final CheckMapper checks;
    private final KitchenTaskService kitchen;
    private final TransactionTemplate read;
    private CheckCollaborationService collaboration;

    @Autowired
    public void livePrebillSource(@Lazy CheckCollaborationService service) {
        collaboration = service;
    }

    private final JsonMapper json = JsonMapper.builder().build();

    public FoodPrintDocumentAssembler(
            OrderBatchMapper batches,
            OrderLineMapper lines,
            CoreLineAllocationMapper allocations,
            ReturnMapper returns,
            ReturnItemMapper returnItems,
            CheckMapper checks,
            KitchenTaskService kitchen,
            PlatformTransactionManager manager) {
        this.batches = batches;
        this.lines = lines;
        this.allocations = allocations;
        this.returns = returns;
        this.returnItems = returnItems;
        this.checks = checks;
        this.kitchen = kitchen;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Original business identity cannot be changed by a new client key; extra paper requires
     * audited reprint.
     */
    public Enqueue freeze(Scope scope, PrintDocumentReq req, String key) {
        validate(scope, req, key);
        Document document =
                "PREBILL".equals(req.scene()) && collaboration != null
                        ? livePrebill(scope, req)
                        : DataScopeContext.executeWithoutScope(
                                () ->
                                        read.execute(
                                                tx ->
                                                        switch (req.scene()) {
                                                            case "ORDER" -> order(scope, req);
                                                            case "KITCHEN" -> kitchen(scope, req);
                                                            case "PREBILL" -> prebill(scope, req);
                                                            case "RETURN" -> returned(scope, req);
                                                            default ->
                                                                    throw BizException.of(
                                                                            ErrorCode.BAD_REQUEST);
                                                        }));
        String event =
                PricingSnapshotHash.of(
                        List.of(
                                "FOOD_PRINT_V1",
                                scope.entityNo(),
                                scope.storeNo(),
                                req.scene(),
                                req.sourceNo(),
                                req.stationNo() == null ? "" : req.stationNo(),
                                "PREBILL".equals(req.scene())
                                        ? PricingSnapshotHash.of(document)
                                        : "ORIGINAL"));
        return new Enqueue(
                event,
                req.stationNo(),
                req.stationNo() == null ? req.sourceNo() : req.stationNo(),
                document);
    }

    private Document order(Scope scope, PrintDocumentReq req) {
        var batch = batch(scope, req.sourceNo());
        return new Document(
                1,
                "ORDER",
                batch.getBatchNo(),
                "订单清单",
                null,
                batchLines(scope, batch).stream()
                        .map(line -> line(batch, line, null, null))
                        .toList(),
                "订单商品清单；不代表收款或财务结算。");
    }

    private Document kitchen(Scope scope, PrintDocumentReq req) {
        if (req.stationNo() == null) bad();
        var batch = batch(scope, req.sourceNo());
        var source = batchLines(scope, batch);
        var tasks =
                kitchen.queue(
                        scope.entityNo(), scope.storeNo(), req.stationNo(), batch.getBatchNo());
        var output = new ArrayList<Line>();
        for (var task : tasks) {
            if (!"MAKE".equals(task.ticketKind())) continue;
            if (!req.stationNo().equals(task.stationNo())
                    || !batch.getBatchNo().equals(task.batchNo())) conflict();
            var line =
                    source.stream()
                            .filter(l -> l.getFoodLineNo().equals(task.foodLineNo()))
                            .findFirst()
                            .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            if (task.qty() == null || task.qty().signum() <= 0) conflict();
            output.add(
                    new Line(
                            label(batch, line),
                            task.qty().stripTrailingZeros().toPlainString(),
                            null,
                            "工位 "
                                    + task.stationNo()
                                    + " / "
                                    + task.taskRole()
                                    + " / 任务 "
                                    + task.taskNo()
                                    + seat(line)));
        }
        if (output.isEmpty()) conflict();
        return new Document(
                1,
                "KITCHEN",
                batch.getBatchNo(),
                "厨房制作单",
                null,
                output,
                "按已冻结任务路线制作；不代表收款。补打需核对原任务，避免重复制作。");
    }

    private Document livePrebill(Scope scope, PrintDocumentReq request) {
        var first =
                collaboration.previewReceivable(
                        scope.entityNo(), scope.storeNo(), request.sourceNo());
        var confirmed =
                collaboration.previewReceivable(
                        scope.entityNo(), scope.storeNo(), request.sourceNo());
        if (!first.sourceHash().equals(confirmed.sourceHash())
                || confirmed.financialActionAllowed()
                || !request.sourceNo().equals(confirmed.checkNo())
                || confirmed.batches().isEmpty()) conflict();
        String currency = null;
        var output = new ArrayList<Line>();
        for (var batch : confirmed.batches()) {
            if (batch.effectiveMinor() == null
                    || batch.effectiveMinor() < 0
                    || batch.currency() == null) conflict();
            if (currency == null) currency = batch.currency();
            if (!currency.equals(batch.currency())) conflict();
            output.add(
                    new Line(
                            "点餐批次 " + batch.batchNo(),
                            "1",
                            batch.effectiveMinor(),
                            "原订单 "
                                    + batch.orderNo()
                                    + " / 原额 "
                                    + batch.originalMinor()
                                    + " / 应收调整 "
                                    + Objects.requireNonNullElse(batch.adjustmentMinor(), 0L)));
        }
        return new Document(
                1,
                "PREBILL",
                request.sourceNo(),
                "预结应收清单",
                currency,
                output,
                "依据原订单有效应收与当前责任归属；来源 " + confirmed.sourceHash() + "。不代表收款成功、财务结清或发票。");
    }

    private Document prebill(Scope scope, PrintDocumentReq req) {
        var check =
                checks.selectOne(
                        Wrappers.<FnbCheck>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("check_no", req.sourceNo()));
        if (check == null) missing();
        var source =
                batches.selectList(
                        Wrappers.<FnbOrderBatch>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("check_no", req.sourceNo())
                                .in("status", "BOUND", "ACCEPTED")
                                .orderByAsc("batch_no"));
        if (source.isEmpty()) conflict();
        var output = new ArrayList<Line>();
        String currency = null;
        for (var batch : source)
            for (var line : batchLines(scope, batch)) {
                var allocated =
                        allocations.selectList(
                                Wrappers.<FnbCoreLineAllocation>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", scope.entityNo())
                                        .eq("food_line_no", line.getFoodLineNo())
                                        .orderByAsc("allocation_no"));
                if (allocated.isEmpty()) conflict();
                long sum = 0;
                for (var allocation : allocated) {
                    if (allocation.getCoreAmountMinor() == null
                            || allocation.getCoreAmountMinor() < 0
                            || allocation.getCurrency() == null
                            || allocation.getCoreSnapshotHash() == null) conflict();
                    if (currency == null) currency = allocation.getCurrency();
                    if (!currency.equals(allocation.getCurrency())) conflict();
                    sum = Math.addExact(sum, allocation.getCoreAmountMinor());
                }
                output.add(line(batch, line, sum, "原订单分摊金额"));
            }
        return new Document(
                1,
                "PREBILL",
                check.getCheckNo(),
                "预结商品清单（原始订单金额）",
                currency,
                output,
                "参考原始订单金额；未扣减已付与退菜，不是实时应收账单、收款凭证或发票。");
    }

    private Document returned(Scope scope, PrintDocumentReq req) {
        var source =
                returns.selectOne(
                        Wrappers.<FnbReturn>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("return_no", req.sourceNo()));
        if (source == null) missing();
        var batch = batch(scope, source.getBatchNo());
        var batchLines = batchLines(scope, batch);
        var items =
                returnItems.selectList(
                        Wrappers.<FnbReturnItem>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("return_no", source.getReturnNo())
                                .orderByAsc("return_item_no"));
        if (items.isEmpty()) conflict();
        var output = new ArrayList<Line>();
        for (var item : items) {
            var line =
                    batchLines.stream()
                            .filter(l -> item.getFoodLineNo().equals(l.getFoodLineNo()))
                            .findFirst()
                            .orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            if (item.getQty() == null || item.getQty().signum() <= 0) conflict();
            output.add(
                    new Line(
                            label(batch, line),
                            item.getQty().stripTrailingZeros().toPlainString(),
                            null,
                            "退菜申请 / " + item.getKitchenDisposition() + seat(line)));
        }
        return new Document(
                1,
                "RETURN",
                source.getReturnNo(),
                "退菜申请清单",
                null,
                output,
                "退菜申请不代表退款完成；退款结果以资金原域记录为准。");
    }

    private FnbOrderBatch batch(Scope scope, String no) {
        var row =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("batch_no", no));
        if (row == null) missing();
        if (!Set.of("BOUND", "ACCEPTED").contains(row.getStatus())) conflict();
        return row;
    }

    private List<FnbOrderLine> batchLines(Scope scope, FnbOrderBatch batch) {
        var result =
                lines.selectList(
                        Wrappers.<FnbOrderLine>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", scope.entityNo())
                                .eq("batch_no", batch.getBatchNo())
                                .orderByAsc("food_line_no"));
        if (result.isEmpty()) conflict();
        for (var line : result)
            if (line.getQty() == null || line.getQty().signum() <= 0 || line.getSkuNo() == null)
                conflict();
        return result;
    }

    private Line line(FnbOrderBatch batch, FnbOrderLine line, Long amount, String note) {
        return new Line(
                label(batch, line),
                line.getQty().stripTrailingZeros().toPlainString(),
                amount,
                (note == null ? "商品 " + line.getGoodsNo() : note) + seat(line));
    }

    /**
     * Human labels come only from the menu composition frozen with this original batch, never
     * today's catalog.
     */
    private String label(FnbOrderBatch batch, FnbOrderLine line) {
        if (batch.getFrozenRequest() == null || line.getDisplaySnapshot() == null)
            return line.getSkuNo();
        try {
            var selected = json.readTree(line.getDisplaySnapshot());
            if (!line.getGoodsNo().equals(selected.path("goodsNo").asString(""))
                    || !line.getSkuNo().equals(selected.path("skuNo").asString("")))
                return line.getSkuNo();
            var frozen = json.readTree(batch.getFrozenRequest());
            if ("CHANNEL".equals(batch.getPayMode())) {
                var carts = frozen.path("cartLines");
                var original = frozen.path("original").path("lines");
                if (carts.isArray() && original.isArray() && carts.size() == original.size()) {
                    String title = null;
                    int matched = 0;
                    for (int i = 0; i < carts.size(); i++) {
                        var cart = carts.get(i);
                        var source = original.get(i);
                        if (!line.getSourceLineRef().equals(cart.path("sourceLineRef").asString(""))) continue;
                        if (!line.getGoodsNo().equals(cart.path("goodsNo").asString(""))
                                || !line.getSkuNo().equals(cart.path("skuNo").asString(""))
                                || !line.getGoodsNo().equals(source.path("goodsNo").asString(""))
                                || !line.getSkuNo().equals(source.path("skuNo").asString(""))) return line.getSkuNo();
                        for (var component : source.path("components")) {
                            if ("MAIN".equals(component.path("externalKind").asString(""))
                                    && line.getGoodsNo().equals(component.path("goodsNo").asString(""))
                                    && line.getSkuNo().equals(component.path("skuNo").asString(""))
                                    && component.path("title").isString()
                                    && !component.path("title").asString().isBlank()) {
                                title = component.path("title").asString();
                                matched++;
                            }
                        }
                    }
                    if (matched == 1) return title + " [" + line.getSkuNo() + "]";
                }
                return line.getSkuNo();
            }
            var items =
                    frozen
                            .path("facts")
                            .path("payload")
                            .path("composition")
                            .path("items");
            if (items.isArray())
                for (var item : items) {
                    if (selected.path("menuNo")
                                    .asString("")
                                    .equals(item.path("menuNo").asString(""))
                            && selected.path("menuItemNo")
                                    .asString("")
                                    .equals(item.path("menuItemNo").asString(""))
                            && line.getGoodsNo().equals(item.path("goodsNo").asString(""))
                            && item.path("title").isString()
                            && !item.path("title").asString().isBlank()) {
                        return item.path("title").asString() + " [" + line.getSkuNo() + "]";
                    }
                }
        } catch (RuntimeException unavailableLegacyDisplay) {
            // A legacy label cannot grant a new fact; the original immutable SKU remains printable.
            return line.getSkuNo();
        }
        return line.getSkuNo();
    }

    private String seat(FnbOrderLine line) {
        return (line.getSeatNo() == null ? "" : " / 座位 " + line.getSeatNo())
                + (line.getCourseNo() == null ? "" : " / 菜序 " + line.getCourseNo());
    }

    private void validate(Scope scope, PrintDocumentReq req, String key) {
        if (scope == null || req == null || key == null || key.isBlank() || key.length() > 128)
            bad();
        id(scope.entityNo());
        id(scope.storeNo());
        id(scope.actorNo());
        id(req.sourceNo());
        if (!Set.of("ORDER", "KITCHEN", "PREBILL", "RETURN")
                .contains(Objects.toString(req.scene(), ""))) bad();
        if (req.stationNo() != null) id(req.stationNo());
        if (!"KITCHEN".equals(req.scene()) && req.stationNo() != null) bad();
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,32}")) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void missing() {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
