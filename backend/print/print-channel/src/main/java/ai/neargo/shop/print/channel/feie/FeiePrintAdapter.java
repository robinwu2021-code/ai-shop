package ai.neargo.shop.print.channel.feie;

import ai.neargo.shop.print.api.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public final class FeiePrintAdapter implements PrintDispatchPort, PrintDeviceProbePort {
    private final FeieHttpClient client;
    private final PrintCredentialLookupPort credentials;
    private final PrintCredentialCipherPort cipher;
    private final boolean nativeProfileVerified;
    private final JsonMapper json = JsonMapper.builder().build();

    public FeiePrintAdapter(
            FeieHttpClient c,
            PrintCredentialLookupPort l,
            PrintCredentialCipherPort cp,
            @Value("${shop.print.feie.native-profile-verified:false}") boolean verified) {
        client = c;
        credentials = l;
        cipher = cp;
        nativeProfileVerified = verified;
    }

    public String adapterCode() {
        return "FEIE";
    }

    public PrintAdapterCapabilities capabilities() {
        return new PrintAdapterCapabilities(
                "FEIE",
                false,
                false,
                true,
                false,
                Set.of(58, 80),
                Set.of(PrintEvidenceLevel.PROVIDER_CONFIRMED));
    }

    private FrozenJson evidence(Map<String, Object> value) {
        String text = json.writeValueAsString(new TreeMap<>(value));
        return new FrozenJson(text, PrintHashes.sha256(text));
    }

    private Map<String, String> secret(PrintScope s, String ref) {
        byte[] bytes = cipher.decrypt(s, credentials.get(s, ref));
        try {
            var n = json.readTree(bytes);
            return Map.of("user", n.path("user").asText(), "ukey", n.path("ukey").asText());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public DispatchResult submit(PrintDispatch dispatch) {
        if (!nativeProfileVerified)
            return new DispatchResult(
                    DispatchOutcome.NOT_ACCEPTED,
                    null,
                    evidence(Map.of("queueTerminated", true, "partialOutput", false)),
                    "print.vendorNativeProfileUnverified");
        var text = new StringBuilder();
        for (var page : dispatch.layout().pages()) {
            for (var d : page.drawings()) {
                if (d instanceof PrintDrawing.TextRun t) {
                    if (t.text().contains("<")
                            || t.text().contains(">")
                            || t.text().codePoints().anyMatch(Character::isISOControl))
                        return rejected("print.vendorTextUnsupported");
                    text.append(t.text()).append("<BR>");
                } else if (d instanceof PrintDrawing.RuleLine) text.append("----------------<BR>");
                else return rejected("print.vendorDrawingUnsupported");
            }
            text.append("<BR>");
        }
        if (text.toString().getBytes(StandardCharsets.UTF_8).length > 5000)
            return rejected("print.vendorPayloadTooLarge");
        try {
            var connection = json.readTree(dispatch.connectionSnapshot().canonicalJson());
            var sn = connection.path("deviceSn").asText();
            if (sn.isBlank()) return rejected("print.vendorDeviceMissing");
            var response =
                    client.call(
                            "printMsg",
                            secret(dispatch.scope(), dispatch.credentialRef()),
                            Map.of("sn", sn, "content", text.toString(), "times", "1"));
            if (response.path("ret").asInt(-999) != 0)
                return new DispatchResult(
                        DispatchOutcome.UNKNOWN,
                        null,
                        evidence(
                                Map.of(
                                        "queueTerminated",
                                        false,
                                        "partialOutput",
                                        false,
                                        "ret",
                                        response.path("ret").asInt(-999))),
                        "print.vendorRejectedUnclassified");
            String id = response.path("data").asText();
            if (id.isBlank())
                return new DispatchResult(
                        DispatchOutcome.UNKNOWN,
                        null,
                        evidence(Map.of("queueTerminated", false, "partialOutput", false)),
                        "print.vendorMissingJobId");
            return new DispatchResult(
                    DispatchOutcome.ACCEPTED,
                    id,
                    evidence(Map.of("queueTerminated", false, "partialOutput", false)),
                    null);
        } catch (Exception e) {
            return new DispatchResult(
                    DispatchOutcome.UNKNOWN,
                    null,
                    evidence(Map.of("queueTerminated", false, "partialOutput", false)),
                    "print.vendorOutcomeUnknown");
        }
    }

    private DispatchResult rejected(String key) {
        return new DispatchResult(
                DispatchOutcome.NOT_ACCEPTED,
                null,
                evidence(Map.of("queueTerminated", true, "partialOutput", false)),
                key);
    }

    public QueryResult query(PrintAttemptRef ref) {
        if (ref.providerJobId() == null)
            return new QueryResult(
                    ObservedPrintStatus.UNKNOWN,
                    PrintEvidenceLevel.NONE,
                    false,
                    false,
                    evidence(Map.of("reason", "NO_PROVIDER_JOB_ID")));
        try {
            var r =
                    client.call(
                            "queryOrderState",
                            secret(ref.scope(), ref.credentialRef()),
                            Map.of("orderid", ref.providerJobId()));
            if (r.path("ret").asInt(-999) == 0
                    && r.path("data").isBoolean()
                    && r.path("data").asBoolean())
                return new QueryResult(
                        ObservedPrintStatus.CONFIRMED,
                        PrintEvidenceLevel.PROVIDER_CONFIRMED,
                        true,
                        false,
                        evidence(Map.of("providerJobId", ref.providerJobId())));
            return new QueryResult(
                    ObservedPrintStatus.UNKNOWN,
                    PrintEvidenceLevel.NONE,
                    false,
                    false,
                    evidence(Map.of("reason", "NOT_CONFIRMED")));
        } catch (Exception e) {
            return new QueryResult(
                    ObservedPrintStatus.UNKNOWN,
                    PrintEvidenceLevel.NONE,
                    false,
                    false,
                    evidence(Map.of("reason", "QUERY_UNKNOWN")));
        }
    }

    public PrinterProbeResult probe(PrintScope scope, String ref, FrozenJson connection) {
        try {
            var n = json.readTree(connection.canonicalJson());
            var r =
                    client.call(
                            "queryPrinterStatus",
                            secret(scope, ref),
                            Map.of("sn", n.path("deviceSn").asText()));
            if (r.path("ret").asInt(-1) != 0)
                return new PrinterProbeResult(
                        PrinterHealthStatus.UNKNOWN,
                        java.time.Instant.now(),
                        "PROVIDER_NOT_CONFIRMED");
            String state = r.path("data").asText();
            var health =
                    state.contains("缺纸")
                            ? PrinterHealthStatus.PAPER_OUT
                            : state.contains("在线")
                                    ? PrinterHealthStatus.ONLINE
                                    : state.contains("离线")
                                            ? PrinterHealthStatus.OFFLINE
                                            : PrinterHealthStatus.UNKNOWN;
            return new PrinterProbeResult(health, java.time.Instant.now(), "PROVIDER_QUERY");
        } catch (Exception e) {
            return new PrinterProbeResult(
                    PrinterHealthStatus.UNKNOWN, java.time.Instant.now(), "QUERY_FAILED");
        }
    }

    public CancelResult cancel(PrintAttemptRef ref) {
        return new CancelResult(
                ObservedPrintStatus.UNKNOWN,
                false,
                false,
                evidence(Map.of("reason", "PER_JOB_CANCEL_UNSUPPORTED")));
    }
}
