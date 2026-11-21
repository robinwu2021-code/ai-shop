package ai.neargo.shop.invoice.channel.turnkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Turnkey 通道：报文落成合法 MIG XML、原子改名、重发覆盖同名文件（PRD AC12）。 */
class TurnkeyInvoiceGatewayTest {

    @TempDir Path root;

    @Test
    void issueMessageBecomesWellFormedNamespacedXmlInTheUploadDirectory() throws Exception {
        var gateway = new TurnkeyInvoiceGateway(root.toString(), "UpCast/B2SSTORAGE/{mig}/SRC",
                JsonMapper.builder().build());
        String payload = """
                {"mig":"F0401","Main":{"InvoiceNumber":"AB00000001","InvoiceDate":"20261121",
                 "Seller":{"Identifier":"12345675","Name":"A&B 小吃"},"Buyer":{"Identifier":"0000000000","Name":"1234"},
                 "RandomNumber":"1234"},
                 "Details":{"ProductItem":[{"Description":"牛肉麵<大>","Amount":"180","SequenceNumber":"1"},
                                           {"Description":"小菜","Amount":"40","SequenceNumber":"2"}]},
                 "Amount":{"SalesAmount":220,"TaxAmount":0,"TotalAmount":220}}""";
        var receipt = gateway.send("TW_EGUI", "ISSUE", payload);
        assertTrue(receipt.accepted());
        Path file = root.resolve("UpCast/B2SSTORAGE/F0401/SRC/F0401_AB00000001.xml");
        assertEquals(file.toString(), receipt.gatewayRef());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var doc = factory.newDocumentBuilder().parse(file.toFile());
        var invoice = doc.getDocumentElement();
        assertEquals("Invoice", invoice.getLocalName());
        assertEquals("urn:GEINV:eInvoiceMessage:F0401:4.0", invoice.getNamespaceURI());
        assertEquals(2, doc.getElementsByTagNameNS("*", "ProductItem").getLength());
        assertEquals("牛肉麵<大>", doc.getElementsByTagNameNS("*", "Description").item(0).getTextContent());
        assertEquals("A&B 小吃", doc.getElementsByTagNameNS("*", "Name").item(0).getTextContent());
        assertEquals(0, doc.getElementsByTagNameNS("*", "mig").getLength());

        assertTrue(gateway.send("TW_EGUI", "ISSUE", payload).accepted(), "retry overwrites, never duplicates");
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void otherRegimesAndUnknownMessagesAreRefused() {
        var gateway = new TurnkeyInvoiceGateway(root.toString(), "{mig}", JsonMapper.builder().build());
        assertFalse(gateway.send("AE_VAT", "ISSUE", "{\"mig\":\"F0401\"}").accepted());
        assertFalse(gateway.send("TW_EGUI", "ISSUE", "{\"mig\":\"X9999\"}").accepted());
    }
}
