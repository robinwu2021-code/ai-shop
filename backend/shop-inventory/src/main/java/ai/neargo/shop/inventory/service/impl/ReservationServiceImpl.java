package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 预留协议实现。 */
@ConditionalOnInventory
@Service
public class ReservationServiceImpl implements ReservationService {

    /** 一次回收多少条。**有上界**：一次全捞会在预留积压时把这一轮任务拖成长事务。 */
    private static final int EXPIRE_BATCH_MAX = 200;

    private final ReservationMapper resMapper;
    private final ReservationLineMapper lineMapper;
    private final BalanceMapper balanceMapper;
    private final OutboundService outbound;
    private final InboundService inbound;

    public ReservationServiceImpl(ReservationMapper resMapper, ReservationLineMapper lineMapper,
                                  BalanceMapper balanceMapper, OutboundService outbound,
                                  InboundService inbound) {
        this.resMapper = resMapper;
        this.lineMapper = lineMapper;
        this.balanceMapper = balanceMapper;
        this.outbound = outbound;
        this.inbound = inbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String reserve(String ownerId, String externalRef, List<Line> lines, long ttlSeconds) {
        InvReservation exists = find(ownerId, externalRef);
        if (exists != null) {
            // 幂等：重试拿回原结果。**不再占一份** —— 占了的话第二份没人释放
            return exists.getReservationId();
        }
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        List<String> shortages = new ArrayList<>();
        for (Line l : lines) {
            // hold 的守卫是 available - qty >= 0：**不动 on_hand**（不变式 I5）——
            // 没付钱的单不该把实存扣掉，但也不该让别人买到同一件货
            if (balanceMapper.hold(ownerId, l.itemId(), l.locationId(), l.qty()) == 0) {
                shortages.add(l.itemId());
            }
        }
        if (!shortages.isEmpty()) {
            // 全成功或全失败：抛出去让事务回滚，已 hold 的那几件一并撤回
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH, String.join(",", shortages));
        }

        String id = InvKeys.next(InvKeys.RESERVATION);
        InvReservation head = new InvReservation();
        head.setReservationId(id);
        head.setOwnerId(ownerId);
        head.setExternalRef(externalRef);
        head.setStatus(InvEnums.ReservationStatus.HELD);
        head.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        resMapper.insert(head);

        int lineNo = 1;
        for (Line l : lines) {
            InvReservationLine row = new InvReservationLine();
            row.setReservationId(id);
            row.setLineNo(lineNo++);
            row.setOwnerId(ownerId);
            row.setItemId(l.itemId());
            row.setLocationId(l.locationId());
            row.setQty(l.qty());
            lineMapper.insert(row);
        }
        return id;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String commit(String ownerId, String externalRef, String operator) {
        InvReservation head = require(ownerId, externalRef);
        if (InvEnums.ReservationStatus.COMMITTED.equals(head.getStatus())) {
            return head.getOutboundNo();   // 幂等：重复 commit 返回原出库单
        }
        if (!InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            // 已释放/已过期的不能再确认 —— 那意味着货已经被别人买走了
            throw BizException.of(ErrorCode.CONFLICT);
        }
        List<InvReservationLine> rows = linesOf(head.getReservationId());

        // 先退预留、再出库：两步都是同一行上的条件更新，事务内由行锁串起来。
        // 反过来做的话，中间那一瞬 available 会凭空多出这批量
        for (InvReservationLine r : rows) {
            balanceMapper.unhold(ownerId, r.getItemId(), r.getLocationId(), r.getQty());
        }
        String locationId = rows.get(0).getLocationId();
        List<OutboundService.Line> outLines = new ArrayList<>();
        for (InvReservationLine r : rows) {
            outLines.add(new OutboundService.Line(r.getItemId(), r.getQty(), null));
        }
        String outboundNo = outbound.postDirectly(new OutboundService.Draft(
                ownerId, locationId, InvEnums.OutboundPurpose.SALE, externalRef,
                head.getReservationId(), null, LocalDateTime.now(), null, outLines), operator);

        head.setStatus(InvEnums.ReservationStatus.COMMITTED);
        head.setCommittedAt(LocalDateTime.now());
        head.setOutboundNo(outboundNo);
        resMapper.updateById(head);
        return outboundNo;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void release(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        // 只作用于 HELD：重复释放不会把预留减两次（超时任务与用户取消会撞车）
        if (head == null || !InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            return;
        }
        unholdAll(head, InvEnums.ReservationStatus.RELEASED);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void releaseByRef(String externalRef) {
        InvReservation head = byRef(externalRef);
        if (head != null) {
            release(head.getOwnerId(), externalRef);
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String commitByRef(String externalRef, String operator) {
        InvReservation head = byRef(externalRef);
        if (head == null) {
            // **不抛**：确认一个不存在的预留，多半是这一单根本没走预留（历史单、
            // 或切换真相源之前下的）。抛出去会让支付回调失败重试，而重试永远不会成功
            return null;
        }
        return commit(head.getOwnerId(), externalRef, operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String restore(String ownerId, String afterSaleNo, List<Line> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<InboundService.Line> inLines = new ArrayList<>();
        for (Line l : lines) {
            inLines.add(new InboundService.Line(l.itemId(), l.qty(), null, null));
        }
        return inbound.postDirectly(new InboundService.Draft(
                ownerId, lines.get(0).locationId(), InvEnums.InboundSource.RETURN, afterSaleNo,
                null, LocalDateTime.now(), null, inLines), operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int expireOverdue(int limit) {
        List<InvReservation> due = resMapper.selectList(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getStatus, InvEnums.ReservationStatus.HELD)
                .lt(InvReservation::getExpiresAt, LocalDateTime.now())
                .last("LIMIT " + Math.min(limit, EXPIRE_BATCH_MAX)));
        due.forEach(h -> unholdAll(h, InvEnums.ReservationStatus.EXPIRED));
        return due.size();
    }

    // ────────────────────────────────────────────────────────────────────

    private void unholdAll(InvReservation head, String toStatus) {
        for (InvReservationLine r : linesOf(head.getReservationId())) {
            balanceMapper.unhold(head.getOwnerId(), r.getItemId(), r.getLocationId(), r.getQty());
        }
        head.setStatus(toStatus);
        head.setReleasedAt(LocalDateTime.now());
        resMapper.updateById(head);
    }

    private List<InvReservationLine> linesOf(String reservationId) {
        return lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, reservationId)
                .orderByAsc(InvReservationLine::getLineNo));
    }

    /** 按单号反查（订单号平台内全局唯一）。 */
    private InvReservation byRef(String externalRef) {
        return resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getExternalRef, externalRef).last("LIMIT 1"));
    }

    private InvReservation find(String ownerId, String externalRef) {
        return resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getOwnerId, ownerId)
                .eq(InvReservation::getExternalRef, externalRef));
    }

    private InvReservation require(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
