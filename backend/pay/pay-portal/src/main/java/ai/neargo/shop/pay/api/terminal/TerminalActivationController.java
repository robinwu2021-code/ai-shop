package ai.neargo.shop.pay.api.terminal;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.terminal.service.TerminalService;
import ai.neargo.shop.pay.terminal.service.TerminalService.Activated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS 终端激活与心跳（pay-guard 开发计划 M6.4；代码清单 §4.1）。激活是本入口唯一不验签的接口（守卫放行，这里验一次性激活码）；
 * 设备密钥只在激活响应里出现这一次，终端存进 Android Keystore。
 */
@RestController
@RequestMapping("/terminal/pay/v1")
public class TerminalActivationController {

    private final TerminalService terminals;

    public TerminalActivationController(TerminalService terminals) {
        this.terminals = terminals;
    }

    record ActivateReq(String activationCode, String deviceFingerprint, String appVersion) {
    }

    record HeartbeatReq(String appVersion, String readerKit) {
    }

    @PostMapping("/activation")
    public Activated activate(@RequestBody ActivateReq req) {
        return terminals.activate(req.activationCode(), req.deviceFingerprint(), req.appVersion());
    }

    @PostMapping("/heartbeat")
    public void heartbeat(PayCaller caller, @RequestBody HeartbeatReq req) {
        terminals.heartbeat(caller.callerId(), req.appVersion(), req.readerKit());
    }
}
