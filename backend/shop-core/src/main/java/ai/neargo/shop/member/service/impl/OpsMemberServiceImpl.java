package ai.neargo.shop.member.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.OpsMemberVO;
import ai.neargo.shop.member.dto.MemberVOs.OpsPersonVO;
import ai.neargo.shop.member.dto.MemberVOs.ReachStatVO;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrReachLog;
import ai.neargo.shop.member.mapper.MemberMappers.MemberMapper;
import ai.neargo.shop.member.mapper.MemberMappers.ReachLogMapper;
import ai.neargo.shop.member.service.OpsMemberService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.PersonPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营侧的会员与人档。
 *
 * <p><b>2026-08-29 起不再整个类绕开数据域。</b>上一版这里写着「跨商家可见是这个页面的
 * 定义」，理由是「数据域 fail-closed，不绕的话配了商家域的运营看到空列表」——
 * <b>那个理由在 {@code mbr_member} 登记 MERCHANT 锚点（{@code entity_no}）之后就不成立了</b>：
 * 配了商家域的运营看到的是<b>那家商家的会员</b>，不是空列表。
 * 留着绕过的后果是「给这个人配了只看某商家」在这几页上完全不生效，而界面上没有任何线索。
 *
 * <p>只剩 {@link #person} 与 {@link #revealPhone} 仍然绕开，且理由不同：
 * <b>人档按定义就是跨商家的</b> —— 它回答「这个人在哪些商家有会员身份」，
 * 按商家裁一刀正好毁掉这一页要说的事。
 *
 * <p>COMMUNITY / PICKUP 两个维度在 {@code mbr_*} 上确实没有锚点（fail-closed → 空白），
 * 但没有任何角色同时持有那两个数据域与 {@code member:*} ——
 * 判据写在 {@code ops-data-scope.test.ts} 的 ANCHOR_WAIVED 里。
 */
@Service
public class OpsMemberServiceImpl implements OpsMemberService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpsMemberServiceImpl.class);

    private static final long DAY = 86_400_000L;

    private final MemberMapper memberMapper;
    private final ReachLogMapper reachMapper;
    private final PersonPort personPort;
    private final MerchantQueryPort merchantPort;
    private final AuditLogPort auditLogPort;

    public OpsMemberServiceImpl(MemberMapper memberMapper, ReachLogMapper reachMapper,
                                PersonPort personPort, MerchantQueryPort merchantPort,
                                AuditLogPort auditLogPort) {
        this.memberMapper = memberMapper;
        this.reachMapper = reachMapper;
        this.personPort = personPort;
        this.merchantPort = merchantPort;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public PageData<OpsMemberVO> members(String entityNo, String phoneTail, long page, long size) {
        long pageNo = Math.max(page, 1);
        long pageSize = size <= 0 ? 20 : Math.min(size, 100);
        // 接数据域（2026-08-29）：mbr_member 有 MERCHANT 锚点，entityNo 这个入参是**过滤**，
        // 数据域是**边界** —— 两者不是一回事：没有数据域时，不传 entityNo 就是全平台会员库。
        var w = Wrappers.<MbrMember>lambdaQuery()
                .eq(entityNo != null && !entityNo.isBlank(), MbrMember::getEntityNo, entityNo)
                .orderByDesc(MbrMember::getId);
        /*
         * 按后四位找人。**只接受恰好四位** —— 给前缀就等于把全平台会员库
         * 变成一本可翻的通讯录，而运营端的读权限比商家端宽得多。
         */
        if (phoneTail != null && !phoneTail.isBlank()) {
            if (phoneTail.length() != 4) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            List<String> personNos = personPort.findByPhoneTail(phoneTail);
            w.in(MbrMember::getPersonNo,
                    personNos.isEmpty() ? List.of("__none__") : personNos);
        }
        Page<MbrMember> p = memberMapper.selectPage(Page.of(pageNo, pageSize), w);
        return PageData.of(p.getRecords().stream().map(this::vo).toList(),
                p.getTotal(), pageNo, pageSize);
    }

    @Override
    public OpsPersonVO person(String personNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var view = personPort.find(personNo)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            List<OpsMemberVO> ms = memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                            .eq(MbrMember::getPersonNo, personNo))
                    .stream().map(this::vo).toList();
            return new OpsPersonVO(personNo, view.phoneTail(), view.userNo(), ms, List.of());
        });
    }

    @Override
    public String revealPhone(String personNo, String reason, String operatorNo) {
        if (reason == null || reason.trim().length() < 4) {
            // 看别人的手机号要说得出为什么。「查一下」这种理由等于没有理由
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 走 {@code PersonPort} 而不是自己解密：**解密密钥属于 user 域**。
         * 上一版这里直接注入了 `PhoneCrypto` 与 `PersonMapper`，member 域因此
         * 长在了 user 域上 —— 而拦它的架构规则常年红着，没给过任何信号。
         *
         * <p>「解不开」与「没这个人」在 Port 那边都归成 empty，这里一并报 NOT_FOUND：
         * 对运营来说这两种情况的下一步动作是一样的（去核对是不是这个人），
         * 而分开报会把一条内部实现细节（密钥换过）暴露到界面上。
         */
        String phone = personPort.revealPhone(personNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        /*
         * **先写审计再返回**。反过来的话，写审计失败时号码已经给出去了，
         * 而这条查看记录永远不存在 —— 事后追责会得出「没人看过」的结论。
         */
        auditLogPort.record("MEMBER_PHONE_REVEAL", personNo,
                "查看完整手机号，理由：" + reason.trim());
        log.info("[member] {} 查看了人档 {} 的手机号，理由：{}", operatorNo, personNo, reason);
        return phone;
    }

    @Override
    public List<ReachStatVO> reachStats(int days) {
        long since = System.currentTimeMillis() - Math.max(days, 1) * DAY;
        // 接数据域（2026-08-29）：mbr_member 登记了 MERCHANT 锚点（entity_no），
        // 配了商家域的运营看到的就该是那家商家的触达数。此前这里绕开了它 ——
        // 「给这个人配了只看某商家」在这一页上完全不生效，而页面上没有任何线索。
        Map<String, Integer> sent = new HashMap<>();
        for (MbrReachLog r : reachMapper.selectList(Wrappers.<MbrReachLog>lambdaQuery()
                .ge(MbrReachLog::getSentAt, since))) {
            sent.merge(r.getEntityNo(), 1, Integer::sum);
        }
        Map<String, int[]> members = new HashMap<>();
        for (MbrMember m : memberMapper.selectList(Wrappers.<MbrMember>lambdaQuery()
                .eq(MbrMember::getStatus, MbrMember.ACTIVE))) {
            int[] c = members.computeIfAbsent(m.getEntityNo(), k -> new int[2]);
            c[0]++;
            if (m.getReachOptOut() != null && m.getReachOptOut() == 1) {
                c[1]++;
            }
        }
        List<ReachStatVO> out = new ArrayList<>();
        for (var e : members.entrySet()) {
            int total = e.getValue()[0];
            int off = e.getValue()[1];
            out.add(new ReachStatVO(e.getKey(), entityName(e.getKey()),
                    sent.getOrDefault(e.getKey(), 0), total, off,
                    total == 0 ? 0d : Math.round(off * 10000d / total) / 100d));
        }
        /*
         * **按退订率倒序，不按发送量**：发得多不是成绩，发到有人关掉才是问题。
         * 按发送量排的话，最需要被看见的那家店会沉在下面。
         */
        out.sort((a, b) -> Double.compare(b.optOutRate(), a.optOutRate()));
        return out;
    }

    private OpsMemberVO vo(MbrMember m) {
        String tail = m.getPersonNo() == null ? null
                : personPort.find(m.getPersonNo()).map(PersonPort.PersonView::phoneTail)
                        .orElse(null);
        return new OpsMemberVO(m.getMemberNo(), m.getPersonNo(), tail,
                m.getEntityNo(), entityName(m.getEntityNo()), m.getStatus(), m.getSource(),
                m.getLevel(), nz(m.getOrderCount()), nz(m.getTotalSpentMinor()),
                m.getReachOptOut() != null && m.getReachOptOut() == 1, nz(m.getJoinedAt()));
    }

    private String entityName(String entityNo) {
        return merchantPort.find(entityNo).map(MerchantQueryPort.MerchantBrief::merchantName)
                .orElse(entityNo);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
