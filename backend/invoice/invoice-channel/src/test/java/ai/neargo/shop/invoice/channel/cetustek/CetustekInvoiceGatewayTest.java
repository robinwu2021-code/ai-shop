package ai.neargo.shop.invoice.channel.cetustek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.InvoiceProviderGateway.ProviderException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.json.JsonMapper;

/**
 * 鲸跃通道对着本机假 SOAP 服务：信封与 UME 一致、内层 XML 作为转义文本送出且可解析、字段按鲸跃规则重排、
 * 结果码判定（含重复上传视为送达）、核验与配号的回包解析（PRD AC28）。
 */
class CetustekInvoiceGatewayTest {

    static final ProviderAccount ACCOUNT = new ProviderAccount("OFFICE-1", "pos<no>", "p&ss");

    HttpServer server;
    final List<String> requests = new ArrayList<>();
    final Deque<String> returns = new ArrayDeque<>();
    CetustekInvoiceGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/InvoiceAPI", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String value = returns.isEmpty() ? "error" : returns.poll();
            byte[] body = ("<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body>"
                    + "<ns2:Resp xmlns:ns2=\"http://webservice.cetustek.com/\"><return>"
                    + CetustekSoapClient.escape(value) + "</return></ns2:Resp></S:Body></S:Envelope>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        gateway = new CetustekInvoiceGateway(new CetustekSoapClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/InvoiceAPI"), Duration.ofSeconds(5)),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void ac28IssueSendsUmeShapedSoapWithEscapedInvoiceXml() throws Exception {
        returns.add("S0");
        var receipt = gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT));
        assertTrue(receipt.accepted());
        assertEquals("CETUSTEK:S0:INV-1", receipt.gatewayRef());

        Document soap = parse(requests.getFirst());
        assertEquals("UploadInvoice", soap.getElementsByTagNameNS("http://webservice.cetustek.com/", "UploadInvoice")
                .item(0).getLocalName());
        assertEquals("OFFICE-1", text(soap, "officeid"));
        assertEquals("pos<no>", text(soap, "posno"), "账号按文本转义，不破坏信封");
        assertEquals("p&ss", text(soap, "posid"));

        Document inv = parse(text(soap, "invoicexml"));
        assertEquals("AB12345678", text(inv, "InvoiceNumber"));
        assertEquals("2026/11/21", text(inv, "InvoiceDate"));
        assertEquals("13:05:09", text(inv, "InvoiceTime"));
        assertEquals("INV-1", text(inv, "OrderId"));
        assertEquals(0, inv.getElementsByTagName("BuyerIdentifier").getLength(), "B2C 不带买方识别码");
        assertEquals("0", text(inv, "DonateMark"), "载具");
        assertEquals("3J0002", text(inv, "CarrierType"));
        assertEquals("/ABC+123", text(inv, "CarrierId1"));
        assertEquals("1050", text(inv, "SaleAmount"));
        assertEquals("1100", text(inv, "TotalAmount"));
        assertEquals("5", text(inv, "PayWay"));
        assertEquals(2, inv.getElementsByTagName("ProductItem").getLength());
        assertEquals("咖啡 <大杯> & 糖", inv.getElementsByTagName("Description").item(0).getTextContent());
        assertEquals("", inv.getElementsByTagName("DType").item(0).getTextContent());
        assertEquals("TN", inv.getElementsByTagName("DType").item(1).getTextContent(), "免税明细");
    }

    @Test
    void ac28ResultCodesDecideDelivery() {
        returns.add("s2");
        assertTrue(gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT)).accepted(),
                "已上传过 = 送达（超时重发不卡死）");
        returns.add("S1");
        var failed = gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT));
        assertFalse(failed.accepted());
        assertEquals("cetustek S1", failed.error());
        assertEquals(Outcome.FINAL, failed.outcome(), "「上传失败」语义未确认前不自动重发");
        returns.add("InValid");
        assertEquals(Outcome.ACCOUNT_PROBLEM,
                gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT)).outcome());
        returns.add("error");
        assertEquals(Outcome.RETRYABLE,
                gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT)).outcome());
        returns.add("M:CarrierId1");
        assertEquals(Outcome.FINAL,
                gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, ACCOUNT)).outcome(),
                "不认识的码不重发");
        returns.add("C5");
        assertTrue(gateway.send(new GatewayRequest("TW_EGUI", "VOID", "INV-1", VOID, ACCOUNT)).accepted());
        returns.add("A6");
        assertTrue(gateway.send(new GatewayRequest("TW_EGUI", "ALLOWANCE", "AL-1", ALLOWANCE, ACCOUNT)).accepted());
        assertEquals(Outcome.ACCOUNT_PROBLEM,
                gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", ISSUE, null)).outcome(), "没配账号同属账号问题");
        assertFalse(gateway.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1",
                ISSUE.replace("\"TaxType\":\"1\",\"TaxRate\"", "\"TaxType\":\"2\",\"TaxRate\""), ACCOUNT)).accepted(),
                "零税率不送出");
    }

    @Test
    void ac28VoidAndAllowanceMapToCetustekFields() throws Exception {
        returns.add("C0");
        gateway.send(new GatewayRequest("TW_EGUI", "VOID", "INV-1", VOID, ACCOUNT));
        Document soap = parse(requests.getFirst());
        assertEquals(1, soap.getElementsByTagNameNS("http://webservice.cetustek.com/", "UploadCancelInvoice")
                .getLength());
        Document cancel = parse(text(soap, "invoicexml"));
        assertEquals("AB12345678", text(cancel, "InvoiceNumber"));
        assertEquals("2026/11/22", text(cancel, "CancelDate"));
        assertEquals("開錯", text(cancel, "Reason"));

        returns.add("A0");
        gateway.send(new GatewayRequest("TW_EGUI", "ALLOWANCE", "AL-1", ALLOWANCE, ACCOUNT));
        soap = parse(requests.get(1));
        Document al = parse(text(soap, "allowancexml"));
        assertEquals("AB1234567801", text(al, "AllowanceNumber"));
        assertEquals("AB12345678", text(al, "InvoiceNumber"));
        assertEquals("13:05:09", text(al, "InvoiceTime"));
        assertEquals("286", text(al, "SaleAmount"));
        assertEquals("14", text(al, "TaxAmount"));
        assertEquals("14", text(al, "Tax"));
    }

    @Test
    void ac28VerifyAndAllocateParseProviderReplies() throws Exception {
        returns.add("<Office><TopID>x</TopID><ID>12345675</ID><Name>示例營業人</Name>"
                + "<QRCode>0123456789ABCDEF0123456789ABCDEF</QRCode></Office>");
        var office = gateway.verify(ACCOUNT);
        assertEquals("12345675", office.taxId());
        assertEquals("示例營業人", office.name());
        assertEquals("0123456789ABCDEF0123456789ABCDEF", office.qrKey());

        returns.add("APPLY-9");
        returns.add("CD;12345600;12345649;07");
        var range = gateway.allocate(ACCOUNT, "11512");
        assertEquals("APPLY-9", range.applyId());
        assertEquals("CD", range.track());
        assertEquals(12_345_600, range.startNo());
        assertEquals(12_345_649, range.endNo());
        assertEquals("07", range.invoiceType());
        Document apply = parse(requests.get(1));
        assertEquals("115", text(apply, "year"));
        assertEquals("11-12", text(apply, "month"));
        assertEquals("APPLY-9", text(parse(requests.get(2)), "applyid"));

        returns.add("InValid");
        assertEquals("invalid", assertThrows(ProviderException.class, () -> gateway.verify(ACCOUNT)).code());
        returns.add("exist");
        assertEquals("exist", assertThrows(ProviderException.class, () -> gateway.allocate(ACCOUNT, "11512")).code());
    }

    @Test
    void ac28UnreachableProviderIsAProviderException() {
        server.stop(0);
        var e = assertThrows(ProviderException.class, () -> gateway.verify(ACCOUNT));
        assertEquals("unreachable", e.code());
    }

    static Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static String text(Document d, String tag) {
        return d.getElementsByTagName(tag).item(0).getTextContent();
    }

    static final String ISSUE = """
            {"mig":"F0401","Main":{"InvoiceNumber":"AB12345678","InvoiceDate":"20261121","InvoiceTime":"13:05:09",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"0000000000","Name":"1234"},
             "InvoiceType":"07","DonateMark":"0","CarrierType":"3J0002","CarrierId1":"/ABC+123","CarrierId2":"/ABC+123",
             "PrintMark":"N","RandomNumber":"1234"},
             "Details":{"ProductItem":[
               {"Description":"咖啡 <大杯> & 糖","Quantity":"2","UnitPrice":"525","TaxType":"1","Amount":"1050","SequenceNumber":"1"},
               {"Description":"報紙","Quantity":"1","UnitPrice":"50","TaxType":"3","Amount":"50","SequenceNumber":"2"}]},
             "Amount":{"SalesAmount":1050,"FreeTaxSalesAmount":50,"ZeroTaxSalesAmount":0,"TaxType":"1","TaxRate":"0.05",
               "TaxAmount":0,"TotalAmount":1100}}""";

    static final String VOID = """
            {"mig":"F0501","CancelInvoiceNumber":"AB12345678","InvoiceDate":"20261121","BuyerId":"0000000000",
             "SellerId":"12345675","CancelDate":"20261122","CancelTime":"09:00:00","CancelReason":"開錯"}""";

    static final String ALLOWANCE = """
            {"mig":"G0401","Main":{"AllowanceNumber":"AB1234567801","AllowanceDate":"20261122",
             "Seller":{"Identifier":"12345675","Name":"示例營業人"},"Buyer":{"Identifier":"0000000000","Name":"1234"},
             "AllowanceType":"2"},
             "Details":{"ProductItem":[{"OriginalInvoiceDate":"20261121","OriginalInvoiceNumber":"AB12345678",
               "OriginalDescription":"退一杯","Quantity":"1","UnitPrice":"286","Amount":"286","Tax":"14",
               "AllowanceSequenceNumber":"1","TaxType":"1"}]},
             "Amount":{"TaxAmount":14,"TotalAmount":286},"ext":{"OriginalInvoiceTime":"13:05:09"}}""";
}
