package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;
import ai.neargo.shop.trade.service.StoreOrderSettlementService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 独立形态（near-pay）的收款码 / 付款链接收款挂门店零售单（TDD-统一订单与开票资料 AC7）。
 *
 * <p>内嵌形态由回调 / 对账补单经 {@code OrderServiceImpl#markPaid} 的支付单号分支挂单；独立形态的回调落在 near-pay，
 * ai-shop 只收到已验签的收款事实（{@link PaymentCollectionService#receive} → {@code MERCHANT_COLLECTION_RECEIVED}）。
 * 这里读那条回执：{@code BIZ_COLLECT} 且 {@code bizNo} 是本店门店零售单的子单号就挂上。门店取回执上核过的门店，
 * 金额取 near-pay 签过的实收。挂单按支付单号幂等，重投无害。
 */
@Component
@ConditionalOnProperty(name = "shop.payment-collections.enabled", havingValue = "true")
public class StoreOrderCollectionConsumer implements OutboxConsumer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StoreOrderSettlementService settlements;

    public StoreOrderCollectionConsumer(DataSource ds, ObjectMapper json, StoreOrderSettlementService settlements) {
        this.jdbc = new JdbcTemplate(ds);
        this.json = json;
        this.settlements = settlements;
    }

    @Override
    public String handlerId() {
        return "store-order-collection-attach";
    }

    @Override
    public boolean supports(String type) {
        return PaymentCollectionWork.TYPE.equals(type);
    }

    @Override
    public void consume(SysOutbox event) {
        String receipt = json.readTree(event.getPayload()).path("receiptNo").asString();
        var row = jdbc.queryForMap("SELECT payload,store_no FROM payment_collection_receipt WHERE receipt_no=?", receipt);
        var fact = json.readValue((String) row.get("payload"), PaymentCollectionFact.class);
        if (!"BIZ_COLLECT".equals(fact.bizType())) return;
        settlements.attachCollection(new PaidCollection(fact.payOrderNo(), (String) row.get("store_no"), fact.bizNo(),
                fact.currency(), fact.paidMinor(), fact.payChannel()));
    }
}
