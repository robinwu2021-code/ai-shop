package ai.neargo.shop.message.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.dto.MessageVOs.TicketVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 客服工单（P-14.2）。
 *
 * <p><b>此前平台端一条工单接口都没有</b>：C 端能提单、能查自己的单，
 * 而客服没有任何入口看到它们——工单落库之后就再没有人碰过。
 * 用户那边 {@code TicketVO} 一直在展示 {@code reply} 字段，
 * 于是他会反复点开看有没有回复。
 *
 * <p>比「漏实现」更值得记的是：{@code msg_ticket} 建表时就留了
 * {@code reply} / {@code replied_at} / {@code replied_by}，注释写着
 * 「记客服身份 —— 代客操作要能追到人」，**设计时就想好了要有这一步**，
 * 但连契约里都没定义过这个动作。漏实现是排期问题，漏定义是没人发现这件事需要做。
 */
@Profile("ops")
@RestController
@Validated
public class OpsTicketController {

    private final MessageService messageService;
    private final AuditLogPort auditLogPort;

    public OpsTicketController(MessageService messageService, AuditLogPort auditLogPort) {
        this.messageService = messageService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 工单队列。
     *
     * <p><b>返回 PageData 而不是裸数组</b>：ops-web 的契约是
     * {@code listTickets(): Promise<Page<Ticket>>}，按 {records,total} 渲染。
     * 返回裸数组的后果不是报错 —— 是<b>接口 200、数据几十条、页面显示「暂无数据」</b>，
     * 而控制台一条错误都没有。
     *
     * <p>工单量级小，全量算完再包一层就够，不必下推到 SQL。
     *
     * @param status 为空给全部；传 {@code OPEN} 就是待处理队列
     */
    @GetMapping("/ops/tickets")
    @PreAuthorize("@perm.can('" + Perms.TICKET_HANDLE + "')")
    public ai.neargo.shop.common.PageData<TicketVO> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ai.neargo.shop.common.PageData.ofAll(messageService.opsTickets(status), page, size);
    }

    /**
     * 客服回复。回复内容**直接发给用户**，署的是平台的名，所以要留痕。
     */
    @PostMapping("/ops/tickets/{ticketNo}/reply")
    @PreAuthorize("@perm.can('" + Perms.TICKET_HANDLE + "')")
    public TicketVO reply(@PathVariable String ticketNo, @RequestBody ReplyReq req) {
        String operator = SecurityUtils.currentUserNo();
        TicketVO vo = messageService.replyTicket(ticketNo, req.reply(), operator);
        auditLogPort.record("TICKET_REPLY", ticketNo, req.reply());
        return vo;
    }

    @PostMapping("/ops/tickets/{ticketNo}/close")
    @PreAuthorize("@perm.can('" + Perms.TICKET_HANDLE + "')")
    public TicketVO close(@PathVariable String ticketNo) {
        String operator = SecurityUtils.currentUserNo();
        TicketVO vo = messageService.closeTicket(ticketNo, operator);
        auditLogPort.record("TICKET_CLOSE", ticketNo, "关闭工单");
        return vo;
    }

    public record ReplyReq(String reply) {
    }
}
