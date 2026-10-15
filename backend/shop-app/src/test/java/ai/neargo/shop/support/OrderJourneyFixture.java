package ai.neargo.shop.support;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Only fixture identities and external TEST receipts are controlled; orders use real C/B HTTP entry points. */
public final class OrderJourneyFixture implements AutoCloseable {
    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TestPayGateway gateway;
    private final String buyer;
    private final String merchant;
    private final String account = "JOURNEY-" + UUID.randomUUID();
    private final String fundsMode;

    public OrderJourneyFixture(WebApplicationContext context, String buyerPhone, String ownerPhone) throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        json = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcTemplate.class);
        gateway = context.getBean(TestPayGateway.class);
        OtpStore otp = context.getBean(OtpStore.class);
        buyer = TestLogin.consumer(mvc, json, otp, buyerPhone);
        postData(buyer, "/mp/user/community", Map.of("communityNo", "C0001", "pickupNo", "PP0001"));
        String ownerToken = TestLogin.consumer(mvc, json, otp, ownerPhone);
        String owner = getData(ownerToken, "/mp/user/profile").path("userNo").asString();
        jdbc.update("insert into mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,"
                + "created_at,updated_at) values (?,'M0001',?,1,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                account, owner);
        merchant = TestLogin.merchantOwner(mvc, json, otp, ownerPhone);
        fundsMode = jdbc.queryForObject("select funds_mode from mch_entity where entity_no='M0001'", String.class);
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no='M0001'");
    }

    public Purchase purchase() throws Exception {
        JsonNode goods = createExpressGoods();
        JsonNode addresses = postData(buyer, "/mp/user/address", Map.of("name", "流程买家", "phone", "13600180013",
                "province", "浙江省", "city", "杭州市", "district", "西湖区", "detail", "文三路 1 号",
                "isDefault", true, "tag", "家"));
        String address = addresses.get(0).path("addressId").asString();
        JsonNode created = postData(buyer, "/mp/order", Map.of("fulfillment", "EXPRESS", "addressId", address,
                "usePoints", 0, "items", List.of(Map.of("goodsNo", goods.path("goodsNo").asString(),
                        "skuNo", goods.path("skus").get(0).path("skuNo").asString(), "qty", 1))));
        String payNo = created.path("payOrderNo").asString();
        postData(buyer, "/mp/order/" + payNo + "/pay", Map.of());
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY'", String.class, payNo);
        assertThat(gateway.markPaid(outNo)).isTrue();
        JsonNode callback = json.valueToTree(Map.of("outTradeNo", outNo,
                "transactionId", gateway.query(outNo).tradeNo(), "sign", "stub-secret"));
        String ack = mvc.perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content(callback.toString())).andReturn().getResponse().getContentAsString();
        assertThat(ack).isEqualTo("SUCCESS");
        String subNo = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, payNo);
        assertThat(getData(buyer, "/mp/order/" + subNo).path("status").asString()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select status from ord_sub_order where sub_order_no=?",
                String.class, subNo)).isEqualTo("WAIT_FULFILL");
        return new Purchase(payNo, subNo);
    }

    private JsonNode createExpressGoods() throws Exception {
        TestStoreCategory.open(mvc, json, merchant, "CAT210");
        String goods = postData(merchant, "/biz/goods/save", Map.of("categoryNo", "CAT210",
                "title", "现金流程商品", "subtitle", "测试", "type", "NORMAL", "cover", "📦",
                "images", List.of(), "specGroups", List.of(), "fulfillments", List.of("EXPRESS"),
                "skus", List.of(Map.of("optionValues", List.of(), "price", 1000, "stock", 10))))
                .path("goodsNo").asString();
        String auth = mvc.perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "goods", "password", "goods123"))))
                .andReturn().getResponse().getContentAsString();
        String ops = data(auth).path("token").asString();
        postData(ops, "/ops/goods/" + goods + "/audit", Map.of("approved", true));
        postData(merchant, "/biz/goods/" + goods + "/toggle", Map.of("onSale", true));
        return getData(buyer, "/mp/goods/" + goods);
    }

    public void prepare(Purchase p) throws Exception {
        postData(merchant, "/biz/order/" + p.subNo() + "/prepare", Map.of());
    }

    public String ship(Purchase p) throws Exception {
        String number = "STO" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase(Locale.ROOT);
        postData(merchant, "/biz/order/" + p.subNo() + "/ship",
                Map.of("expressCompany", "STO", "expressNo", number));
        return number;
    }

    public JsonNode buyerDetail(Purchase p) throws Exception { return getData(buyer, "/mp/order/" + p.subNo()); }
    public JsonNode merchantDetail(Purchase p) throws Exception { return getData(merchant, "/biz/order/" + p.subNo()); }
    public void receive(Purchase p) throws Exception {
        postData(buyer, "/mp/order/" + p.subNo() + "/confirm-receipt", Map.of());
    }

    public String applyRefund(Purchase p) throws Exception {
        return postData(buyer, "/mp/order/" + p.subNo() + "/after-sale",
                Map.of("type", "REFUND_ONLY", "reason", "不想要了", "reasonCode", "NOT_WANTED"))
                .path("afterSaleNo").asString();
    }

    public JsonNode approveRefund(String afterSale) throws Exception {
        return postData(merchant, "/biz/after-sale/" + afterSale + "/approve", Map.of("remark", "全额现金退款"));
    }

    public MockMvc mvc() { return mvc; }
    public TestPayGateway gateway() { return gateway; }

    private JsonNode postData(String token, String path, Object body) throws Exception {
        String response = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString();
        return data(response);
    }

    private JsonNode getData(String token, String path) throws Exception {
        return data(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode data(String response) {
        JsonNode envelope = json.readTree(response);
        assertThat(envelope.path("code").asInt()).as(response).isZero();
        return envelope.path("data");
    }

    @Override
    public void close() {
        jdbc.update("delete from mch_account where mch_account_no=?", account);
        jdbc.update("update mch_entity set funds_mode=? where entity_no='M0001'", fundsMode);
    }

    public record Purchase(String payNo, String subNo) { }
}
