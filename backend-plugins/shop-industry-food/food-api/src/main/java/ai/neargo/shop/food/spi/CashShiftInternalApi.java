package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CashShiftContracts.CashShiftVO;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** 拆分部署时宿主里的 food-web 经此调 food-server 的现金交班；业务码随请求摘要回传。 */
public interface CashShiftInternalApi {
    String ROOT = "/internal/food/cash-shifts";

    record Submit(String entity, String store, String actor, SubmitReq request, String key) {
    }

    record Reference(String entity, String store, String shiftNo) {
    }

    @PostExchange(ROOT + "/submit")
    CashShiftVO submit(@RequestBody Submit request);

    @PostExchange(ROOT + "/get")
    CashShiftVO get(@RequestBody Reference request);

    static String digest(String operation, String entity, String store, String subject) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    String.join("|", "CASH_SHIFT_V1", operation, entity, store, subject)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
