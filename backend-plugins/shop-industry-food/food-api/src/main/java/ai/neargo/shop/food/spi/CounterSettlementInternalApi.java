package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** 拆分部署时 food-web（宿主）经此调 food-server 的柜台后付结账；业务码随请求摘要回传。 */
public interface CounterSettlementInternalApi {
    String ROOT = "/internal/food/counter-settlements";

    record Create(String entity, String store, String actor, String checkNo, String key) {
    }

    record Finalize(String entity, String store, String actor, String settlementNo, List<String> offlineRecordNos) {
    }

    record Reference(String entity, String store, String actor, String settlementNo) {
    }

    @PostExchange(ROOT + "/create")
    CounterSettlementVO create(@RequestBody Create request);

    @PostExchange(ROOT + "/finalize")
    CounterSettlementVO finalizeSettlement(@RequestBody Finalize request);

    @PostExchange(ROOT + "/cancel")
    CounterSettlementVO cancel(@RequestBody Reference request);

    @PostExchange(ROOT + "/get")
    CounterSettlementVO get(@RequestBody Reference request);

    static String digest(String operation, Object... parts) {
        var text = new StringBuilder("COUNTER_SETTLEMENT_V1|").append(operation);
        for (Object part : parts) {
            text.append('|').append(part);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String digest(Create r) {
        return digest("create", r.entity(), r.store(), r.actor(), r.checkNo(), r.key());
    }

    static String digest(Finalize r) {
        return digest("finalize", r.entity(), r.store(), r.actor(), r.settlementNo(), r.offlineRecordNos());
    }

    static String digest(String operation, Reference r) {
        return digest(operation, r.entity(), r.store(), r.actor(), r.settlementNo());
    }
}
