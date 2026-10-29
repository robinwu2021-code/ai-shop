package ai.neargo.inventory.svc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-17：充电桩<b>只改档案</b>即可接入 —— 用与 {@code ProfileCodecTest} 同一份 {@code charging.json}，
 * 在设备套上开业主、装档案、签凭证，然后走完到货验收入库 → 发到场站（桩 + 模块 + 成套枪线）→ 撤回整套回仓 →
 * 维修领用 → 复核盘点。过程中不改任何代码、不加任何配置。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_charging;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=DEVICE",
        "inventory.instance.allowed-systems=SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class ChargingPileProfileE2ETest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 与领域层档案夹具同一份文件：档案就是接入的全部 */
    private static final Path PROFILE = Path.of("../inventory-domain/src/test/resources/profiles/charging.json");

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;

    MockMvc mvc;
    String bearer;
    String pile;
    String module;
    String cable;
    String wh;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        String owner = id(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"CHARGING\",\"externalRef\":\"" + UUID.randomUUID() + "\",\"name\":\"桩\"}")), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + Files.readString(PROFILE) + ",\"reason\":\"init\"}"))
                .andExpect(status().isOk());
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        bearer = "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();

        String acceptance = "\"trackingMode\":\"SERIAL\",\"gatePolicy\":\"REF_REQUIRED\",\"gateRefType\":\"ACCEPTANCE\"";
        pile = id(write(put("/api/inventory/v1/items/by-ref/PILE").content("{\"name\":\"直流桩\"," + acceptance + "}"),
                "E1"), "itemId");
        module = id(write(put("/api/inventory/v1/items/by-ref/MODULE").content("{\"name\":\"功率模块\"," + acceptance
                + "}"), "E1"), "itemId");
        cable = id(write(put("/api/inventory/v1/items/by-ref/GUN").content("{\"name\":\"枪线\"}"), "E1"), "itemId");
        wh = id(write(post("/api/inventory/v1/warehouses").content("{\"name\":\"华南仓\"}"), "E1"), "locationId");
        target("PROJECT", "PRJ-1", "前海项目", null);
        target("STATION", "ST-1", "前海一号站", "PRJ-1");
        target("PERSON", "E9", "安装工", null);

        // 到货验收入库：桩、挂在桩里的模块都要验收单；枪线按数量
        String in = id(write(post("/api/inventory/v1/inbounds").content(("""
                {"locationId":"%s","purpose":"PURCHASE","lines":[
                  {"itemId":"%s","serials":[{"serialNo":"P-1","gateRef":"ACC-1"},{"serialNo":"P-2","gateRef":"ACC-2"}]},
                  {"itemId":"%s","serials":[{"serialNo":"M-1","gateRef":"ACC-3","parentSerialNo":"P-1"}]},
                  {"itemId":"%s","qty":10}]}""").formatted(wh, pile, module, cable)), "E1"), "docNo");
        write(post("/api/inventory/v1/inbounds/" + in + "/post"), "E1").andExpect(status().isOk());
    }

    @Test
    void profileOnlyOnboarding() throws Exception {
        // 发到场站：扫两根桩（P-1 带出模块），每根桩成套两根枪线
        String issue = id(write(post("/api/inventory/v1/outbounds").content(("""
                {"locationId":"%s","purpose":"ISSUE","targets":{"STATION":"ST-1","PERSON":"E9"},
                 "serialNos":["P-1","P-2"],
                 "lines":[{"itemId":"%s","qty":2,"parentSerialNo":"P-1"},
                          {"itemId":"%s","qty":2,"parentSerialNo":"P-2"}]}""").formatted(wh, cable, cable)), "E1"),
                "docNo");
        JsonNode doc = JSON.readTree(body(read("/api/inventory/v1/outbounds/" + issue)));
        assertThat(doc.get("lines").findValuesAsString("parentSerialNo")).contains("P-1", "P-2");
        write(post("/api/inventory/v1/outbounds/" + issue + "/post"), "E1").andExpect(status().isOk());
        assertThat(onHand(pile)).isEqualByComparingTo("0");
        assertThat(onHand(module)).isEqualByComparingTo("0");
        assertThat(onHand(cable)).isEqualByComparingTo("6");

        // 出库池：桩是主记录，模块与枪线都是它的从属记录
        JsonNode all = JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?outboundNo=" + issue)));
        String head = null;
        for (JsonNode r : all) {
            if ("P-1".equals(r.get("serialNo").asString(null))) {
                head = r.get("dispatchId").asString();
            }
        }
        assertThat(head).isNotNull();
        int children = 0;
        for (JsonNode r : all) {
            if (head.equals(r.get("parentDispatchId").asString(null))) {
                children++;
                if (r.get("serialNo").isNull()) {
                    assertThat(new BigDecimal(r.get("qty").asString())).isEqualByComparingTo("2");
                }
            }
        }
        assertThat(children).isEqualTo(2);

        // 单独撤枪线 409；撤桩 → 桩、模块、两根枪线整套回仓
        String cableChild = null;
        for (JsonNode r : all) {
            if (head.equals(r.get("parentDispatchId").asString(null)) && r.get("serialNo").isNull()) {
                cableChild = r.get("dispatchId").asString();
            }
        }
        write(post("/api/inventory/v1/dispatch-pool/" + cableChild + "/revoke").content("{\"reason\":\"x\"}"), "E1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.dispatch.child_locked"));
        write(post("/api/inventory/v1/dispatch-pool/" + head + "/revoke").content("{\"reason\":\"站点延期\"}"), "E1")
                .andExpect(status().isOk());
        assertThat(onHand(pile)).isEqualByComparingTo("1");
        assertThat(onHand(module)).isEqualByComparingTo("1");
        assertThat(onHand(cable)).isEqualByComparingTo("8");

        // 维修领用：必填领用人，带工单号，不进出库池
        String consume = id(write(post("/api/inventory/v1/outbounds").content(
                ("{\"locationId\":\"%s\",\"purpose\":\"CONSUME\",\"reasonCode\":\"WO-77\","
                        + "\"targets\":{\"PERSON\":\"E9\"},\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}")
                        .formatted(wh, cable)), "E1"), "docNo");
        write(post("/api/inventory/v1/outbounds/" + consume + "/post"), "E1").andExpect(status().isOk());
        assertThat(onHand(cable)).isEqualByComparingTo("7");
        assertThat(JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?outboundNo=" + consume)))).isEmpty();
        write(post("/api/inventory/v1/outbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"CONSUME\",\"lines\":[{\"itemId\":\"%s\",\"qty\":1}]}"
                        .formatted(wh, cable)), "E1").andExpect(status().isBadRequest());

        // 档案要求复核且复核人不同：提交人自己批不了
        String count = id(write(post("/api/inventory/v1/counts").content(
                "{\"locationId\":\"%s\",\"itemIds\":[\"%s\"]}".formatted(wh, cable)), "E1"), "docNo");
        write(put("/api/inventory/v1/counts/" + count + "/lines/" + cable).content("{\"countedQty\":7}"), "E1")
                .andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/submit"), "E1").andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/approve").content("{\"note\":\"ok\"}"), "E1")
                .andExpect(status().isForbidden());
        write(post("/api/inventory/v1/counts/" + count + "/approve").content("{\"note\":\"ok\"}"), "E2")
                .andExpect(status().isOk());
        write(post("/api/inventory/v1/counts/" + count + "/post"), "E2").andExpect(status().isOk());
    }

    @Test
    void kitLineMustHangOnATopLevelSerialOfTheSameDocument() throws Exception {
        // 不在单上的桩
        write(post("/api/inventory/v1/outbounds").content(("""
                {"locationId":"%s","purpose":"ISSUE","targets":{"STATION":"ST-1","PERSON":"E9"},
                 "serialNos":["P-2"],"lines":[{"itemId":"%s","qty":2,"parentSerialNo":"P-1"}]}""")
                .formatted(wh, cable)), "E1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("inventory.bad_request"));
        // 被带出的下级件（模块）不能再挂成套行：成套只有一层
        write(post("/api/inventory/v1/outbounds").content(("""
                {"locationId":"%s","purpose":"ISSUE","targets":{"STATION":"ST-1","PERSON":"E9"},
                 "serialNos":["P-1"],"lines":[{"itemId":"%s","qty":2,"parentSerialNo":"M-1"}]}""")
                .formatted(wh, cable)), "E1").andExpect(status().isBadRequest());
        // 分批追加：先扫桩、后补枪线，照样挂得上
        String no = id(write(post("/api/inventory/v1/outbounds").content(("""
                {"locationId":"%s","purpose":"ISSUE","targets":{"STATION":"ST-1","PERSON":"E9"},
                 "serialNos":["P-2"]}""").formatted(wh)), "E1"), "docNo");
        write(patch("/api/inventory/v1/outbounds/" + no)
                .content("{\"lines\":[{\"itemId\":\"%s\",\"qty\":1,\"parentSerialNo\":\"P-2\"}]}".formatted(cable)), "E1")
                .andExpect(status().isOk());
        write(post("/api/inventory/v1/outbounds/" + no + "/post"), "E1").andExpect(status().isOk());
        JsonNode all = JSON.readTree(body(read("/api/inventory/v1/dispatch-pool?outboundNo=" + no)));
        assertThat(all).hasSize(2);
        assertThat(all.findValuesAsString("parentDispatchId").stream().filter(v -> v != null && !v.isEmpty()))
                .hasSize(1);
    }

    private BigDecimal onHand(String item) throws Exception {
        JsonNode b = JSON.readTree(body(read("/api/inventory/v1/balances?itemId=" + item)));
        return b.isEmpty() ? BigDecimal.ZERO : new BigDecimal(b.get(0).get("onHand").asString());
    }

    private void target(String type, String ref, String name, String parent) throws Exception {
        write(put("/api/inventory/v1/external-targets/" + type + "/" + ref).content(
                "{\"name\":\"%s\",\"sourceVersion\":1%s}".formatted(name,
                        parent == null ? "" : ",\"parentRef\":\"" + parent + "\"")), "E1").andExpect(status().isOk());
    }

    private ResultActions write(MockHttpServletRequestBuilder req, String staff) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:" + staff));
    }

    private MvcResult read(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String id(ResultActions r, String field) throws Exception {
        return JSON.readTree(body(r.andExpect(status().isOk()).andReturn())).get(field).asString();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
