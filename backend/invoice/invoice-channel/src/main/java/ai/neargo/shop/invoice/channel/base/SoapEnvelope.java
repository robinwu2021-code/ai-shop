package ai.neargo.shop.invoice.channel.base;

import java.util.Map;

/** SOAP 1.1 信封：按顺序输出参数、值按 XML 文本转义；回包取 {@code <return>}。 */
public final class SoapEnvelope {

    private SoapEnvelope() {
    }

    public static String build(String namespace, String operation, Map<String, String> fields) {
        var out = new StringBuilder("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"")
                .append(" xmlns:pm=\"").append(namespace).append("\"><soapenv:Header></soapenv:Header><soapenv:Body>")
                .append("<pm:").append(operation).append('>');
        fields.forEach((k, v) -> out.append('<').append(k).append('>').append(escape(v == null ? "" : v))
                .append("</").append(k).append('>'));
        return out.append("</pm:").append(operation).append("></soapenv:Body></soapenv:Envelope>").toString();
    }

    public static String between(String s, String open, String close) {
        int i = s.indexOf(open);
        if (i < 0) return null;
        int j = s.indexOf(close, i + open.length());
        return j < 0 ? null : s.substring(i + open.length(), j);
    }

    public static String escape(String s) {
        var out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // XML 1.0 不允许的控制字符直接丢掉，免得整张报文被对方判 XML 格式错误
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') out.append(c);
                }
            }
        }
        return out.toString();
    }

    public static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#xD;", "\r").replace("&#13;", "\r").replace("&amp;", "&");
    }
}
