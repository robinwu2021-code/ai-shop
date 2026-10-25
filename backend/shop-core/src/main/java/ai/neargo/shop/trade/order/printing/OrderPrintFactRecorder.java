package ai.neargo.shop.trade.order.printing;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.*;
import java.time.*;
import java.util.*;

@Component
public class OrderPrintFactRecorder {
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final OrderItemMapper items;
    private final ObjectMapper json;

    public OrderPrintFactRecorder(
            PaymentMapper o, StoreOrderMapper s, OrderItemMapper i, ObjectMapper j) {
        orders = o;
        subs = s;
        items = i;
        json = j;
    }

    public OrderPrintFact capturePaidSubOrder(
            String orderNo, String subNo, ConfirmedPayment confirmed) {
        var order = orders.selectOne(new QueryWrapper<OrdOrder>().eq("order_no", orderNo));
        var sub =
                subs.selectOne(
                        new QueryWrapper<OrdSubOrder>()
                                .eq("order_no", orderNo)
                                .eq("sub_order_no", subNo));
        if (order == null || sub == null)
            throw new IllegalStateException("Print source order missing");
        var lines =
                items.selectList(
                        new QueryWrapper<OrdItem>()
                                .eq("order_no", orderNo)
                                .eq("sub_order_no", subNo)
                                .orderByAsc("id"));
        String tenant = order.getTenantNo() == null ? "MAIN" : order.getTenantNo();
        String store =
                sub.getStoreNo() == null || sub.getStoreNo().isBlank()
                        ? "UNASSIGNED"
                        : sub.getStoreNo();
        var diagnostics = new ArrayList<String>();
        if (store.equals("UNASSIGNED")) diagnostics.add("print.sourceStoreMissing");
        if (sub.getEntityNo() == null)
            throw new IllegalStateException("Paid suborder has no entity");
        if (lines.isEmpty() || lines.size() > 500) diagnostics.add("print.sourceLinesInvalid");
        Long goods = sub.getGoodsAmount(),
                discount = sub.getDiscountAmount(),
                freight = sub.getFreightAmount(),
                paid = sub.getPayAmount();
        if (goods == null || discount == null || freight == null || paid == null)
            diagnostics.add("print.sourceMoneyMissing");
        else if (BigInteger.valueOf(goods)
                        .subtract(BigInteger.valueOf(discount))
                        .add(BigInteger.valueOf(freight))
                        .compareTo(BigInteger.valueOf(paid))
                != 0) diagnostics.add("print.sourceCompositionUnsupported");
        if (sub.getWeighAdjustMinor() != null && sub.getWeighAdjustMinor() != 0)
            diagnostics.add("print.sourceAdjustmentUnsupported");
        String currency = order.getCurrency();
        int scale = 2;
        try {
            scale = Currency.getInstance(currency).getDefaultFractionDigits();
            if (scale < 0) throw new IllegalArgumentException();
        } catch (Exception e) {
            diagnostics.add("print.sourceCurrencyMissing");
            currency = currency == null ? "" : currency;
        }
        if (order.getPaidAt() == null || order.getCreatedAt() == null)
            diagnostics.add("print.sourceTimeMissing");
        String identity =
                (order.getPayAmount() != null && order.getPayAmount() == 0)
                        ? "NO_CASH:" + orderNo
                        : confirmed.channel() + ":" + confirmed.tradeNo();
        if (!identity.startsWith("NO_CASH:")
                && (confirmed.tradeNo() == null || confirmed.tradeNo().isBlank()))
            diagnostics.add("print.sourcePaymentEvidenceMissing");
        String factNo = "OF" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        String revision = String.valueOf(sub.getVersion() == null ? 0 : sub.getVersion());
        Instant occurred =
                order.getPaidAt() == null ? Instant.EPOCH : Instant.ofEpochMilli(order.getPaidAt());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("scene", "ORDER_PAID");
        payload.put("payloadSchemaVersion", 1);
        payload.put(
                "store",
                Map.of(
                        "name",
                        sub.getEntityName() == null ? sub.getEntityNo() : sub.getEntityName(),
                        "timezone",
                        "Asia/Shanghai"));
        payload.put(
                "source",
                Map.of(
                        "type",
                        "SUB_ORDER",
                        "no",
                        subNo,
                        "factNo",
                        factNo,
                        "revision",
                        revision,
                        "occurredAt",
                        occurred.toString()));
        payload.put("currency", currency);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("orderNo", orderNo);
        fields.put(
                "orderCreatedAt",
                order.getCreatedAt() == null
                        ? null
                        : order.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
        fields.put("paidAt", occurred.toString());
        fields.put("fulfillmentLabel", sub.getFulfillment() == null ? "" : sub.getFulfillment());
        fields.put("pickupCode", sub.getVerifyCode());
        fields.put("goodsAmount", money(goods, currency, scale));
        fields.put("discountAmount", money(discount, currency, scale));
        fields.put("freightAmount", money(freight, currency, scale));
        fields.put("paidAmount", money(paid, currency, scale));
        fields.put("paymentNo", identity.startsWith("NO_CASH:") ? null : confirmed.tradeNo());
        fields.put("orderRemark", sub.getRemark());
        var rows = new ArrayList<Map<String, Object>>();
        BigInteger qty = BigInteger.ZERO, lineTotal = BigInteger.ZERO;
        for (var line : lines) {
            if (line.getQty() == null || line.getAmount() == null || line.getTitle() == null) {
                diagnostics.add("print.sourceLineIncomplete");
                continue;
            }
            qty = qty.add(BigInteger.valueOf(line.getQty()));
            lineTotal = lineTotal.add(BigInteger.valueOf(line.getAmount()));
            var row = new LinkedHashMap<String, Object>();
            row.put("lineRef", String.valueOf(line.getId()));
            row.put("title", line.getTitle());
            row.put("variant", line.getSpec());
            row.put("quantity", String.valueOf(line.getQty()));
            row.put("unit", "");
            row.put("lineAmount", money(line.getAmount(), currency, scale));
            rows.add(row);
        }
        if (goods != null && !lineTotal.equals(BigInteger.valueOf(goods)))
            diagnostics.add("print.sourceGoodsMismatch");
        fields.put("goodsQuantity", qty.toString());
        payload.put("fields", fields);
        payload.put("lines", rows);
        var payment = new LinkedHashMap<String, Object>();
        payment.put("method", identity.startsWith("NO_CASH:") ? "NO_CASH" : confirmed.channel());
        payment.put("label", identity.startsWith("NO_CASH:") ? "免支付" : confirmed.channel());
        payment.put("amount", money(paid, currency, scale));
        if (!identity.startsWith("NO_CASH:")) payment.put("tradeNo", confirmed.tradeNo());
        payload.put("payments", List.of(payment));
        var f = new OrderPrintFact();
        f.setTenantNo(tenant);
        f.setEntityNo(sub.getEntityNo());
        f.setStoreNo(store);
        f.setPrintFactNo(factNo);
        f.setOrderNo(orderNo);
        f.setSubOrderNo(subNo);
        f.setSourceFactKey(PrintHashes.sha256(identity + ":" + subNo));
        f.setReceiptIntentKey(PrintHashes.sha256("RECEIPT:" + identity + ":" + subNo));
        f.setBizRevision(revision);
        f.setScene("ORDER_PAID");
        f.setTriggerType("ORDER_PAYMENT_CONFIRMED");
        f.setPayloadSchemaVersion(1);
        f.setSourceOccurredAt(LocalDateTime.ofInstant(occurred, ZoneOffset.UTC));
        f.setSnapshot(json.writeValueAsString(sorted(json.valueToTree(payload))));
        f.setSnapshotHash(PrintHashes.sha256(f.getSnapshot()));
        f.setPrintability(diagnostics.isEmpty() ? "READY" : "BLOCKED");
        f.setBlockCode(diagnostics.isEmpty() ? null : diagnostics.getFirst());
        f.setDiagnostics(json.writeValueAsString(diagnostics.stream().distinct().toList()));
        f.setRevision(0L);
        f.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        f.setUpdatedAt(f.getCreatedAt());
        return f;
    }

    private Object money(Long minor, String currency, int scale) {
        if (minor == null) return null;
        return Map.of("minor", minor.toString(), "currency", currency, "scale", scale);
    }

    private JsonNode sorted(JsonNode n) {
        if (n.isObject()) {
            var out = json.createObjectNode();
            var keys = new TreeSet<String>();
            n.propertyNames().forEach(keys::add);
            for (var key : keys) out.set(key, sorted(n.get(key)));
            return out;
        }
        if (n.isArray()) {
            var out = json.createArrayNode();
            n.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return n;
    }
}
