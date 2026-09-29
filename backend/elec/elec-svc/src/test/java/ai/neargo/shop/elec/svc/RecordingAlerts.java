package ai.neargo.shop.elec.svc;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的企业微信：<b>继承真实实现、只替换发送那一步</b>，所以测到的是真实的「方法 → 消息文案」映射。
 * 仍然返回 false —— 与「没配 webhook」同一个语义，不改变其它测试看到的「没送到」。
 */
public class RecordingAlerts extends WeComElecAlerts {

    /** 一条发出去的消息：kind 与 markdown 正文 */
    public record Sent(String kind, String markdown) {
    }

    private final List<Sent> sent = java.util.Collections.synchronizedList(new ArrayList<>());

    public RecordingAlerts(ObjectMapper json) {
        super("", json);
    }

    @Override
    boolean send(String kind, String markdown) {
        sent.add(new Sent(kind, markdown));
        return false;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** 正文里含这个单号的那些 */
    public List<Sent> about(String rfqNo) {
        return sent().stream().filter(s -> s.markdown().contains(rfqNo)).toList();
    }
}
