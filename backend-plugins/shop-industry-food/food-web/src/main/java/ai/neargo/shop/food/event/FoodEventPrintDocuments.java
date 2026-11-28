package ai.neargo.shop.food.event;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.Line;
import ai.neargo.shop.spi.printing.PrintPort.Scope;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 事件单据（厨房变更、桌台变更、标签）：按到达的原始业务事件定格内容，只打变化部分，不打改后的整单。
 * 原来按事件号回读厨显事件表，厨显事件表退役后改为直接用事件本身（TDD-餐饮域迁移新库 §2.8 D-3）。
 */
final class FoodEventPrintDocuments {
    private final ObjectMapper json;

    FoodEventPrintDocuments(ObjectMapper json) {
        this.json = json;
    }

    Enqueue freeze(Scope scope, PrintDocumentReq req, String key, EventDraft event) {
        if (scope == null
                || req == null
                || key == null
                || key.isBlank()
                || key.length() > 128
                || !Set.of("KITCHEN_CHANGE", "TABLE_CHANGE", "LABEL").contains(req.scene())) {
            bad();
        }
        id(scope.entityNo());
        id(scope.storeNo());
        id(scope.actorNo());
        id(req.sourceNo());
        if (req.stationNo() != null) {
            id(req.stationNo());
        }
        if (event == null || !req.sourceNo().equals(event.eventNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (event.stationNo() != null && !Objects.equals(event.stationNo(), req.stationNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var source = json.readTree(event.payload());
        var lines = new ArrayList<Line>();
        String title;
        if ("TABLE_CHANGE".equals(req.scene())) {
            if (!"CHECK_CHANGED".equals(event.eventType())) {
                bad();
            }
            title = "桌台变更通知";
            lines.add(
                    new Line(
                            event.sourceNo(),
                            "1",
                            null,
                            "原桌 "
                                    + source.path("before").path("tableNos")
                                    + " / 新桌 "
                                    + source.path("after").path("tableNos")));
        } else if ("ORDER_CHANGED".equals(event.eventType())
                && "KITCHEN_CHANGE".equals(req.scene())) {
            title = "订单业务变更通知";
            var changed = source.path("foodLineNos");
            if (!changed.isArray() || changed.size() == 0) {
                lines.add(
                        new Line(
                                event.sourceNo(),
                                "1",
                                null,
                                source.path("action").asString("CHANGE") + " / 业务申请，不表示厨房撤菜或退款完成"));
            } else {
                for (var changedLine : changed) {
                    lines.add(
                            new Line(
                                    changedLine.asString(),
                                    "1",
                                    null,
                                    source.path("action").asString("CHANGE")
                                            + " / 关联行标识，数量以原变更单为准"));
                }
            }
        } else {
            if (!"KITCHEN_ACTION".equals(event.eventType())
                    && !"ORDER_CHANGED".equals(event.eventType())) {
                bad();
            }
            String qty =
                    source.path("qty").isString()
                            ? source.path("qty").asString()
                            : source.path("qty").toString();
            try {
                if (new BigDecimal(qty).signum() <= 0) {
                    bad();
                }
            } catch (NumberFormatException failure) {
                bad();
            }
            title = "LABEL".equals(req.scene()) ? "制作标签" : "厨房变更通知";
            lines.add(
                    new Line(
                            source.path("foodLineNo").asString(event.sourceNo()),
                            qty,
                            null,
                            source.path("action").asString("CHANGE")
                                    + " / 原任务 "
                                    + event.sourceNo()
                                    + " / 工位 "
                                    + Objects.toString(event.stationNo(), "")));
        }
        var document =
                new Document(
                        1,
                        req.scene(),
                        req.sourceNo(),
                        title,
                        null,
                        lines,
                        "原业务事件通知；不是收付款凭证。补打须核对原事件，避免重复制作。");
        return new Enqueue(
                PricingSnapshotHash.of(
                        List.of(
                                "FOOD_EVENT_PRINT_V1",
                                scope.entityNo(),
                                scope.storeNo(),
                                event.eventNo(),
                                req.scene(),
                                Objects.toString(req.stationNo(), ""))),
                req.stationNo(),
                event.eventNo(),
                document);
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,32}")) {
            bad();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
