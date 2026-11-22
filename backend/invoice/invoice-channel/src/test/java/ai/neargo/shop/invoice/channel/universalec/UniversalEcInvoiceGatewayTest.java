package ai.neargo.shop.invoice.channel.universalec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.invoice.gateway.BlankRangeCapability;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import ai.neargo.shop.invoice.gateway.TrackCapability;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 汎宇通道对着本机假汎宇：报文按《電子發票POS機傳輸規格書 JSON MIG4.1》组装、ERROR_CODE 判定、
 * 核验（Y01）与领号（A01 / C01）的回包解析（TDD-发票-汎宇接入 §5）。
 */
class UniversalEcInvoiceGatewayTest {

    static final ProviderAccount ACCOUNT = new ProviderAccount("12345675", "1001", "secret-possn-xyz");
    /** 2026-11-22 10:00 台北 → 当期 11512，下一期 11602。 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));

    final JsonMapper json = JsonMapper.builder().build();
    HttpServer server;
    final List<String> requests = new ArrayList<>();
    final List<String> contentTypes = new ArrayList<>();
    final Deque<String> replies = new ArrayDeque<>();
    UniversalEcInvoiceGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/GetInvoice.ashx", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = (replies.isEmpty() ? reply("0000") : replies.poll()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        gateway = new UniversalEcInvoiceGateway(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/GetInvoice.ashx"),
                Duration.ofSeconds(5), json, CLOCK);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---- AC1 ----

    @Test
    void declares_tw_provider_range_with_account() {
        assertEquals("UNIVERSALEC", gateway.code());
        assertTrue(gateway.profile().supports("TW_EGUI"));
        assertFalse(gateway.profile().supports("AE_EINVOICE"));
        assertEquals(NumberingMode.PROVIDER_RANGE, gateway.profile().numbering());
        assertEquals(ReportMode.ASYNC_UPLOAD, gateway.profile().reporting());
        assertTrue(gateway.profile().requiresAccount());
    }

    // ---- AC2 / AC5 ----

    @Test
    void issue_posts_c0401_with_account_fields() {
        GatewayReceipt receipt = send("ISSUE", "AB12345678", ISSUE);
        assertTrue(receipt.accepted());
        assertEquals("UNIVERSALEC:0000:AB12345678", receipt.gatewayRef());
        assertTrue(contentTypes.getFirst().startsWith("application/json"));

        JsonNode inv = lastRequest().path("Invoice");
        assertEquals("C0401", inv.path("A1").asText());
        assertEquals("AB12345678", inv.path("A2").asText());
        assertEquals("2026-11-21", inv.path("A3").asText(), "日期转 yyyy-MM-dd");
        assertEquals("13:05:09", inv.path("A4").asText());
        assertEquals("0000000000", inv.path("A5").asText(), "B2C 买方 10 个 0");
        assertEquals("1234", inv.path("A6").asText());
        assertEquals("07", inv.path("A22").asText());
        assertEquals("1234", inv.path("A30").asText());
        assertEquals("12345675", inv.path("D1").asText(), "卖方 = 账号 SELLERID");
        assertEquals("secret-possn-xyz", inv.path("D2").asText(), "通道金钥只在报文里");
        assertEquals("1001", inv.path("D3").asText());
        assertEquals("2026-11-22 10:00:00", inv.path("D4").asText(), "系统时间用台北时间");
        assertEquals("0000000000000000", inv.path("ACCOUNT").asText());

        JsonNode items = inv.path("B");
        assertEquals(2, items.size());
        assertEquals("1", items.get(0).path("B1").asText());
        assertEquals("咖啡 <大杯> & 糖", items.get(0).path("B2").asText());
        assertEquals("2", items.get(0).path("B3").asText());
        assertEquals("525", items.get(0).path("B5").asText());
        assertEquals("1050", items.get(0).path("B6").asText());
        assertEquals("1", items.get(0).path("B7").asText());

        assertEquals("1050", inv.path("C1").asText());
        assertEquals("50", inv.path("C2").asText());
        assertEquals("0", inv.path("C3").asText());
        assertEquals("0.05", inv.path("C5").asText());
        assertEquals("0", inv.path("C6").asText());
        assertEquals("1100", inv.path("C7").asText());
    }

    @Test
    void mixed_tax_keeps_line_tax_types() {
        send("ISSUE", "AB12345678", ISSUE);
        JsonNode inv = lastRequest().path("Invoice");
        assertEquals("9", inv.path("C4").asText(), "明细含应税与免税 → 主档课税别 9");
        assertEquals("1", inv.path("B").get(0).path("B13").asText());
        assertEquals("3", inv.path("B").get(1).path("B13").asText());
    }

    @Test
    void carrier_and_donation_fields() {
        send("ISSUE", "AB12345678", ISSUE);
        JsonNode carrier = lastRequest().path("Invoice");
        assertEquals("0", carrier.path("A24").asText());
        assertEquals("3J0002", carrier.path("A25").asText());
        assertEquals("/ABC+123", carrier.path("A26").asText());
        assertEquals("/ABC+123", carrier.path("A27").asText());
        assertEquals("N", carrier.path("A28").asText());
        assertEquals("", carrier.path("A29").asText());

        send("ISSUE", "AB12345679", DONATED);
        JsonNode donated = lastRequest().path("Invoice");
        assertEquals("1", donated.path("A24").asText());
        assertEquals("", donated.path("A25").asText(), "捐赠时载具须为空");
        assertEquals("8585", donated.path("A29").asText());

        send("ISSUE", "AB12345680", B2B_PRINTED);
        JsonNode b2b = lastRequest().path("Invoice");
        assertEquals("53212539", b2b.path("A5").asText());
        assertEquals("示例買方股份有限公司", b2b.path("A6").asText());
        assertEquals("Y", b2b.path("A28").asText());
        assertEquals("1", b2b.path("C4").asText());
        assertEquals("1000", b2b.path("C1").asText());
        assertEquals("50", b2b.path("C6").asText());
    }

    @Test
    void zero_rated_is_rejected_before_sending() {
        var receipt = send("ISSUE", "AB12345678",
                ISSUE.replace("\"ZeroTaxSalesAmount\":0", "\"ZeroTaxSalesAmount\":50"));
        assertEquals(Outcome.FINAL, receipt.outcome());
        assertTrue(requests.isEmpty(), "零税率缺原因与通关方式，不送半对的票");
    }

    @Test
    void void_posts_c0501() {
        assertTrue(send("VOID", "AB12345678", VOID).accepted());
        JsonNode v = lastRequest().path("Invoice");
        assertEquals("C0501", v.path("INVOICE_CODE").asText());
        assertEquals("secret-possn-xyz", v.path("POSSN").asText());
        assertEquals("1001", v.path("POSID").asText());
        assertEquals("AB12345678", v.path("INVOICE_NUMBER").asText());
        assertEquals("2026-11-21", v.path("INVOICE_DATE").asText());
        assertEquals("0000000000", v.path("BUYERID").asText());
        assertEquals("12345675", v.path("SELLERID").asText());
        assertEquals("2026-11-22", v.path("CANCEL_DATE").asText());
        assertEquals("09:00:00", v.path("CANCEL_TIME").asText());
        assertEquals("開錯", v.path("CANCEL_REASON").asText());
        assertEquals("2026-11-22 10:00:00", v.path("SYSTIME").asText());
    }

    @Test
    void allowance_posts_d0401n() {
        assertTrue(send("ALLOWANCE", "AB1234567801", ALLOWANCE).accepted());
        JsonNode a = lastRequest().path("Invoice");
        assertEquals("D0401N", a.path("INVOICE_CODE").asText());
        assertEquals("12345675", a.path("SELLERID").asText());
        assertEquals("1001", a.path("POSID").asText());
        assertEquals("secret-possn-xyz", a.path("POSSN").asText());
        assertEquals("AB1234567801", a.path("ALLOWANCENUMBER").asText());
        assertEquals("2026-11-22", a.path("ALLOWANCEDATE").asText());
        assertEquals("0000000000", a.path("IDENTIFIER").asText());
        assertEquals("1234", a.path("NAME").asText());
        assertEquals("2", a.path("ALLOWANCETYPE").asText());
        assertEquals("14", a.path("TAXAMOUNT").asText());
        assertEquals("286", a.path("TOTALAMOUNT").asText());
        JsonNode item = a.path("PRODUCTITEM").get(0);
        assertEquals("2026-11-21", item.path("ORIGINALINVOICEDATE").asText());
        assertEquals("AB12345678", item.path("ORIGINALINVOICENUMBER").asText());
        assertEquals("退一杯", item.path("ORIGINALDESCRIPTION").asText());
        assertEquals("1", item.path("QUANTITY").asText());
        assertEquals("286", item.path("UNITPRICE").asText());
        assertEquals("286", item.path("AMOUNT").asText());
        assertEquals("14", item.path("TAX").asText());
        assertEquals("1", item.path("ALLOWANCESEQUENCENUMBER").asText());
        assertEquals("1", item.path("TAXTYPE").asText());
        assertFalse(item.has("ORIGINALSEQUENCENUMBER"), "原明细序号税制没有，不编造");
    }

    @Test
    void allowance_void_posts_d0501() {
        assertTrue(send("ALLOWANCE_VOID", "AB1234567801", ALLOWANCE_VOID).accepted());
        JsonNode v = lastRequest().path("Invoice");
        assertEquals("D0501", v.path("INVOICE_CODE").asText());
        assertEquals("AB1234567801", v.path("INVOICE_NUMBER").asText());
        assertEquals("2026-11-22", v.path("INVOICE_DATE").asText(), "原折让开立日期");
        assertEquals("2026-11-23", v.path("CANCEL_DATE").asText());
        assertEquals("2", v.path("ALLOWANCETYPE").asText());
    }

    @Test
    void receipt_never_carries_possn() {
        replies.add(reply("S030"));
        var failed = send("ISSUE", "AB12345678", ISSUE);
        assertFalse(failed.error().contains("secret-possn-xyz"));
        assertFalse(ACCOUNT.toString().contains("secret-possn-xyz"));
    }

    // ---- AC3 / AC4 ----

    @Test
    void duplicate_issue_e02_is_accepted() {
        replies.add(reply("E02"));
        assertTrue(send("ISSUE", "AB12345678", ISSUE).accepted(), "已存在 = 送达（超时重发不卡死）");
    }

    @Test
    void duplicate_void_e07_is_accepted() {
        replies.add(reply("E07"));
        assertTrue(send("VOID", "AB12345678", VOID).accepted());
        replies.add(reply("E02"));
        assertEquals(Outcome.FINAL, send("VOID", "AB12345678", VOID).outcome(), "E02 只对开立算送达");
    }

    @Test
    void account_codes_pause_the_entity() {
        for (String code : List.of("S020", "S022", "S024", "S026")) {
            replies.add(reply(code));
            assertEquals(Outcome.ACCOUNT_PROBLEM, send("ISSUE", "AB12345678", ISSUE).outcome(), code);
        }
        assertEquals(Outcome.ACCOUNT_PROBLEM, gateway.send(
                new GatewayRequest("TW_EGUI", "ISSUE", "AB12345678", ISSUE, null)).outcome());
    }

    @Test
    void busy_codes_are_retryable() {
        for (String code : List.of("S010", "S012", "S030", "S032", "S034")) {
            replies.add(reply(code));
            assertEquals(Outcome.RETRYABLE, send("ISSUE", "AB12345678", ISSUE).outcome(), code);
        }
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"C0401\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗：取得流水號失敗,取號時間過長\"}}");
        assertEquals(Outcome.RETRYABLE, send("ISSUE", "AB12345678", ISSUE).outcome(), "REPLY=0 未给码");
    }

    @Test
    void unknown_code_is_final() {
        replies.add(reply("U012"));
        var r = send("ISSUE", "AB12345678", ISSUE);
        assertEquals(Outcome.FINAL, r.outcome());
        assertEquals("universalec U012", r.error());
        replies.add(reply("X999"));
        assertEquals(Outcome.FINAL, send("ISSUE", "AB12345678", ISSUE).outcome());
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"C0401\",\"REPLY\":\"-1\",\"MESSAGE\":\"失敗\"}}");
        assertEquals(Outcome.FINAL, send("ISSUE", "AB12345678", ISSUE).outcome());
    }

    // ---- AC6 ----

    @Test
    void verify_uses_y01() {
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Y01\",\"REPLY\":\"1\",\"MESSAGE\":\"成功\",\"POSID\":\"1001\","
                + "\"SELLERID\":\"12345675\",\"SYSTIME\":\"2026/11/22 10:00:01\",\"QRCodeASKey\":\"null\"}}");
        var office = gateway.verify(ACCOUNT);
        assertEquals("12345675", office.taxId());
        JsonNode idx = lastRequest().path("INDEX");
        assertEquals("Y01", idx.path("FUNCTIONCODE").asText());
        assertEquals("12345675", idx.path("SELLERID").asText());
        assertEquals("1001", idx.path("POSID").asText());
        assertEquals("secret-possn-xyz", idx.path("POSSN").asText());
        assertEquals("2026-11-22 10:00:00", idx.path("SYSTIME").asText());
    }

    @Test
    void verify_failure_throws() {
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Y01\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗: 請確認 POS 機已註冊且無停用\"}}");
        var e = assertThrows(ProviderException.class, () -> gateway.verify(ACCOUNT));
        assertEquals("invalid", e.code());
    }

    // ---- AC7 ----

    @Test
    void allocate_current_period_uses_a01() {
        replies.add(range("A01", "11512", "DC", "51705750", "51705799"));
        var r = gateway.allocate(ACCOUNT, "11512");
        assertEquals("A01", lastRequest().path("INDEX").path("FUNCTIONCODE").asText());
        assertEquals("DC", r.track());
        assertEquals(51705750, r.startNo());
        assertEquals(51705799, r.endNo());
        assertEquals("07", r.invoiceType());
        assertEquals("UNIVERSALEC:11512:DC51705750", r.applyId());
    }

    @Test
    void allocate_next_period_uses_c01() {
        replies.add(range("C01", "11602", "KK", "81705750", "81705799"));
        var r = gateway.allocate(ACCOUNT, "11602");
        assertEquals("C01", lastRequest().path("INDEX").path("FUNCTIONCODE").asText());
        assertEquals("KK", r.track());
    }

    @Test
    void allocate_other_period_or_mismatch_throws() {
        assertEquals("period_unsupported",
                assertThrows(ProviderException.class, () -> gateway.allocate(ACCOUNT, "11510")).code());
        assertTrue(requests.isEmpty(), "不支持的期别不打汎宇");

        replies.add(range("A01", "11510", "DC", "51705750", "51705799"));
        assertEquals("period_mismatch",
                assertThrows(ProviderException.class, () -> gateway.allocate(ACCOUNT, "11512")).code());

        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"A01\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗:資料庫中,無發票配號可用\"}}");
        assertEquals("nodata", assertThrows(ProviderException.class, () -> gateway.allocate(ACCOUNT, "11512")).code());
    }

    // ---- P2：字轨与空白字轨 ----

    @Test
    void track_mode_uses_z33() {
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z33\",\"REPLY\":\"1\",\"MESSAGE\":\"成功\",\"YEAR_MONTH\":\"11602\","
                + "\"INVSTATUS\":\"2\",\"ERROR_CODE\":\"0000\"}}");
        assertEquals(TrackCapability.AllocationMode.MANUAL, gateway.allocationMode(ACCOUNT, "11602"));
        JsonNode z = lastRequest().path("Invoice");
        assertEquals("Z33", z.path("INVOICE_CODE").asText());
        assertEquals("11602", z.path("YEAR_MONTH").asText());
        assertEquals("12345675", z.path("SELLERID").asText());
        assertEquals("1001", z.path("POSID").asText());
        assertEquals("secret-possn-xyz", z.path("POSSN").asText());

        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z33\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗：查無發票期別配號\"}}");
        assertEquals(TrackCapability.AllocationMode.NONE, gateway.allocationMode(ACCOUNT, "11602"), "查無 = 无配号记录");
    }

    @Test
    void downloaded_tracks_use_z31_and_drop_placeholders() {
        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z31\",\"REPLY\":\"1\",\"MESSAGE\":\"成功\",\"ERROR_CODE\":\"0000\","
                + "\"INVOICE_DATA\":[{\"YEAR_MONTH\":\"11512\",\"TYPE\":\"07\",\"INVOICE_TRACK\":\"AB\","
                + "\"INVOICE_START\":\"00000000\",\"INVOICE_END\":\"00000099\"},"
                + "{\"YEAR_MONTH\":\"11512\",\"TYPE\":\"07\",\"INVOICE_TRACK\":\"00\","
                + "\"INVOICE_START\":\"00000000\",\"INVOICE_END\":\"00000000\"}]}}");
        var tracks = gateway.downloadedTracks(ACCOUNT, "11512");
        assertEquals(List.of(new TrackCapability.TrackRange("07", "AB", 0, 99)), tracks);
        assertEquals("Z31", lastRequest().path("Invoice").path("INVOICE_CODE").asText());

        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z31\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗：查無已下載的發票期別字軌\"}}");
        assertTrue(gateway.downloadedTracks(ACCOUNT, "11512").isEmpty());

        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z31\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗：POS 機不存在\"}}");
        assertEquals("失敗：POS 機不存在",
                assertThrows(ProviderException.class, () -> gateway.downloadedTracks(ACCOUNT, "11512")).code(),
                "其余失败把汎宇说明交给用户");
    }

    @Test
    void upload_next_tracks_uses_z32() {
        replies.add(reply("0000"));
        gateway.uploadNextTracks(ACCOUNT, new TrackCapability.TrackUpload("11602", "07", "AB", "12345675",
                List.of(new TrackCapability.TrackRange("07", "AB", 10000050, 10050099))));
        JsonNode z = lastRequest().path("Invoice");
        assertEquals("Z32", z.path("INVOICE_CODE").asText());
        assertEquals("11602", z.path("YEAR_MONTH").asText());
        assertEquals("07", z.path("INVOICE_TYPE").asText());
        assertEquals("AB", z.path("INVOICE_TRACK").asText());
        assertEquals("2026-11-22 10:00:00", z.path("SYSTIME").asText());
        JsonNode item = z.path("INVOICE_ITEM").get(0);
        assertEquals("12345675", item.path("COMP_ID").asText());
        assertEquals("10000050", item.path("INVOICE_START").asText());
        assertEquals("10050099", item.path("INVOICE_END").asText());

        replies.add("{\"INDEX\":{\"FUNCTIONCODE\":\"Z32\",\"REPLY\":\"0\",\"MESSAGE\":\"失敗：字軌區間不存在\"}}");
        assertThrows(ProviderException.class, () -> gateway.uploadNextTracks(ACCOUNT,
                new TrackCapability.TrackUpload("11602", "07", "AB", "12345675",
                        List.of(new TrackCapability.TrackRange("07", "AB", 0, 49)))));
    }

    @Test
    void report_blank_uses_e0402_with_eight_digit_numbers() {
        replies.add(reply("0000"));
        gateway.reportBlank(ACCOUNT, new BlankRangeCapability.BlankReport("11512", "07", "AB", "12345675",
                List.of(new TrackCapability.TrackRange("07", "AB", 742200, 742210),
                        new TrackCapability.TrackRange("07", "AB", 992150, 992150))));
        JsonNode e = lastRequest().path("Invoice");
        assertEquals("E0402", e.path("INVOICE_CODE").asText());
        assertEquals("secret-possn-xyz", e.path("POSSN").asText());
        assertEquals("12345675", e.path("Main").path("HeadBan").asText());
        assertEquals("12345675", e.path("Main").path("BranchBan").asText());
        assertEquals("07", e.path("Main").path("InvoiceType").asText());
        assertEquals("11512", e.path("Main").path("YearMonth").asText());
        assertEquals("AB", e.path("Main").path("InvoiceTrack").asText());
        JsonNode items = e.path("Details").path("BranchTrackBlankItem");
        assertEquals("00742200", items.get(0).path("InvoiceBeginNo").asText());
        assertEquals("00742210", items.get(0).path("InvoiceEndNo").asText());
        assertEquals("00992150", items.get(1).path("InvoiceBeginNo").asText());

        replies.add(reply("S030"));
        assertThrows(ProviderException.class, () -> gateway.reportBlank(ACCOUNT,
                new BlankRangeCapability.BlankReport("11512", "07", "AB", "12345675", List.of())));
    }

    // ---- helpers & fixtures ----

    private GatewayReceipt send(String type, String refNo, String payload) {
        return gateway.send(new GatewayRequest("TW_EGUI", type, refNo, payload, ACCOUNT));
    }

    private JsonNode lastRequest() {
        return json.readTree(requests.getLast());
    }

    static String reply(String errorCode) {
        String r = "0000".equals(errorCode) || "E02".equals(errorCode) || "E07".equals(errorCode)
                ? ("0000".equals(errorCode) ? "1" : "-1") : "-1";
        return "{\"INDEX\":{\"FUNCTIONCODE\":\"C0401\",\"REPLY\":\"" + r + "\",\"MESSAGE\":\"x\",\"POSID\":\"1001\","
                + "\"SELLERID\":\"12345675\",\"POSSN\":\"secret-possn-xyz\",\"SYSTIME\":\"2026/11/22 10:00:01\","
                + "\"INVOICENUMBER\":null,\"ERROR_CODE\":\"" + errorCode + "\"}}";
    }

    static String range(String fn, String taxMonth, String header, String start, String end) {
        return "{\"INDEX\":{\"FUNCTIONCODE\":\"" + fn + "\",\"REPLY\":\"1\",\"MESSAGE\":\"成功\",\"POSID\":\"1001\","
                + "\"SELLERID\":\"12345675\",\"SYSTIME\":\"2026/11/22 10:00:01\",\"TAXMONTH\":\"" + taxMonth + "\","
                + "\"INVOICEHEADER\":\"" + header + "\",\"INVOICESTART\":\"" + start + "\",\"INVOICEEND\":\"" + end + "\","
                + "\"QRCodeASKey\":\"5F2BF5EEA62517788999A08024437\",\"TYPE\":\"07\"}}";
    }

    static final String ISSUE = """
            {"mig":"F0401","Main":{"InvoiceNumber":"AB12345678","InvoiceDate":"20261121","InvoiceTime":"13:05:09",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"0000000000","Name":"1234"},
             "InvoiceType":"07","DonateMark":"0","CarrierType":"3J0002","CarrierId1":"/ABC+123","CarrierId2":"/ABC+123",
             "PrintMark":"N","RandomNumber":"1234"},
             "Details":{"ProductItem":[
               {"Description":"咖啡 <大杯> & 糖","Quantity":"2","UnitPrice":"525","TaxType":"1","Amount":"1050","SequenceNumber":"1"},
               {"Description":"報紙","Quantity":"1","UnitPrice":"50","TaxType":"3","Amount":"50","SequenceNumber":"2"}]},
             "Amount":{"SalesAmount":1050,"FreeTaxSalesAmount":50,"ZeroTaxSalesAmount":0,"TaxType":"9","TaxRate":"0.05",
               "TaxAmount":0,"TotalAmount":1100}}""";
    static final String DONATED = """
            {"mig":"F0401","Main":{"InvoiceNumber":"AB12345679","InvoiceDate":"20261121","InvoiceTime":"13:05:09",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"0000000000","Name":"5678"},
             "InvoiceType":"07","DonateMark":"1","PrintMark":"N","NPOBAN":"8585","RandomNumber":"5678"},
             "Details":{"ProductItem":[
               {"Description":"咖啡","Quantity":"1","UnitPrice":"100","TaxType":"1","Amount":"100","SequenceNumber":"1"}]},
             "Amount":{"SalesAmount":100,"FreeTaxSalesAmount":0,"ZeroTaxSalesAmount":0,"TaxType":"1","TaxRate":"0.05",
               "TaxAmount":0,"TotalAmount":100}}""";
    static final String B2B_PRINTED = """
            {"mig":"F0401","Main":{"InvoiceNumber":"AB12345680","InvoiceDate":"20261121","InvoiceTime":"13:05:09",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"53212539","Name":"示例買方股份有限公司"},
             "InvoiceType":"07","DonateMark":"0","PrintMark":"Y","RandomNumber":"9012"},
             "Details":{"ProductItem":[
               {"Description":"咖啡豆","Quantity":"1","UnitPrice":"1050","TaxType":"1","Amount":"1050","SequenceNumber":"1"}]},
             "Amount":{"SalesAmount":1000,"FreeTaxSalesAmount":0,"ZeroTaxSalesAmount":0,"TaxType":"1","TaxRate":"0.05",
               "TaxAmount":50,"TotalAmount":1050}}""";
    static final String VOID = """
            {"mig":"F0501","CancelInvoiceNumber":"AB12345678","InvoiceDate":"20261121","BuyerId":"0000000000",
             "SellerId":"12345675","CancelDate":"20261122","CancelTime":"09:00:00","CancelReason":"開錯"}""";
    static final String ALLOWANCE_VOID = """
            {"mig":"G0501","CancelAllowanceNumber":"AB1234567801","AllowanceDate":"20261122","BuyerId":"0000000000",
             "SellerId":"12345675","CancelDate":"20261123","CancelTime":"10:00:00","CancelReason":"開錯"}""";
    static final String ALLOWANCE = """
            {"mig":"G0401","Main":{"AllowanceNumber":"AB1234567801","AllowanceDate":"20261122",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"0000000000","Name":"1234"},
             "AllowanceType":"2"},
             "Details":{"ProductItem":[{"OriginalInvoiceDate":"20261121","OriginalInvoiceNumber":"AB12345678",
               "OriginalDescription":"退一杯","Quantity":"1","UnitPrice":"286","Amount":"286","Tax":"14",
               "AllowanceSequenceNumber":"1","TaxType":"1"}]},
             "Amount":{"TaxAmount":14,"TotalAmount":286},"ext":{"OriginalInvoiceTime":"13:05:09"}}""";
}
