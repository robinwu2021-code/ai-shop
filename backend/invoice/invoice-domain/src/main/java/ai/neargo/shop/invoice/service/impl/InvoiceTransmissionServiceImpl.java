package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.invoice.entity.IvcTransmission;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.TransmissionMapper;
import ai.neargo.shop.invoice.service.InvoiceTransmissionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 上传任务的唯一执行者（重试只在这里，不在通道、不在开立流程）。
 *
 * <p>先用条件更新把任务「认领」成 SENDING 再发 —— 多实例同时跑也只有一个实例发同一条；
 * 通道抛异常或拒收都记 FAILED 并按 1、2、4 … 分钟（封顶 60）退避，下一轮原样补发同一报文。
 */
@Service
public class InvoiceTransmissionServiceImpl implements InvoiceTransmissionService {

    private final TransmissionMapper transmissions;
    private final Map<String, InvoiceGateway> gateways;

    public InvoiceTransmissionServiceImpl(TransmissionMapper transmissions, List<InvoiceGateway> gateways) {
        this.transmissions = transmissions;
        this.gateways = gateways.stream().collect(Collectors.toMap(InvoiceGateway::code, Function.identity()));
    }

    @Override
    public int transmitDue(int limit) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        // SENDING 超过 10 分钟没回音 = 上一个执行者在发送途中退出；报文幂等由通道按发票号识别，重发安全
        var due = transmissions.selectList(new QueryWrapper<IvcTransmission>()
                .and(w -> w.in("status", "PENDING", "FAILED").le("next_attempt_at", now)
                        .or(x -> x.eq("status", "SENDING").le("updated_at", now.minusMinutes(10))))
                .orderByAsc("next_attempt_at", "id").last("LIMIT " + limit));
        int sent = 0;
        for (var t : due) {
            if (transmissions.update(null, new UpdateWrapper<IvcTransmission>().eq("id", t.getId())
                    .eq("status", t.getStatus()).eq("attempts", t.getAttempts())
                    .set("status", "SENDING").set("updated_at", now)) != 1) continue;
            var gateway = gateways.get(t.getGatewayCode());
            InvoiceGateway.GatewayReceipt receipt;
            try {
                receipt = gateway == null
                        ? new InvoiceGateway.GatewayReceipt(false, null, "gateway not installed: " + t.getGatewayCode())
                        : gateway.send(t.getRegimeCode(), t.getMessageType(), t.getPayload());
            } catch (RuntimeException e) {
                // 业务动作：通道异常即本次未送达，转为可重试状态；原报文留着下一轮补发
                receipt = new InvoiceGateway.GatewayReceipt(false, null, String.valueOf(e.getMessage()));
            }
            int attempts = t.getAttempts() + 1;
            var after = LocalDateTime.now(ZoneOffset.UTC);
            var update = new UpdateWrapper<IvcTransmission>().eq("id", t.getId()).eq("status", "SENDING")
                    .set("attempts", attempts).set("updated_at", after);
            if (receipt.accepted()) {
                update.set("status", "SENT").set("gateway_ref", receipt.gatewayRef()).set("last_error", null);
                sent++;
            } else {
                String error = receipt.error() == null ? "rejected" : receipt.error();
                update.set("status", "FAILED")
                        .set("last_error", error.length() > 512 ? error.substring(0, 512) : error)
                        .set("next_attempt_at", after.plusMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6))));
            }
            transmissions.update(null, update);
        }
        return sent;
    }
}
