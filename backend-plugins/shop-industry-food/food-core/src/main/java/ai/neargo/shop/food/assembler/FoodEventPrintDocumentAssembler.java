package ai.neargo.shop.food.assembler;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
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

/** Prints immutable event deltas, never the current whole order after a change. */
public class FoodEventPrintDocumentAssembler {
    private final FoodKdsMapper events;
    private final ObjectMapper json;

    public FoodEventPrintDocumentAssembler(FoodKdsMapper events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    public Enqueue freeze(Scope scope, PrintDocumentReq req, String key) {
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
        var event = events.originalEvent(scope.entityNo(), scope.storeNo(), req.sourceNo());
        if (event == null) {
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
