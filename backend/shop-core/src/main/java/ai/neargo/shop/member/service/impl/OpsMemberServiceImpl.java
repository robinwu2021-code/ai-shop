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
 * <p><b>整个类都跑在 {@code executeWithoutScope} 里</b>：运营会话的维度是
 * MERCHANT/COMMUNITY/PICKUP，而这里要的恰恰是<b>跨商家</b>看 ——
 * 数据域是 fail-closed 的，不绕的话配了商家域的运营会看到空列表而不是报错。
 * 「跨商家可见」是这个页面的定义，不是它的漏洞。
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
    private final ai.neargo.shop.user.service.PhoneCrypto phoneCrypto;
    private final ai.neargo.shop.user.mapper.UserMappers.PersonMapper personMapper;

    public OpsMemberServiceImpl(MemberMapper memberMapper, ReachLogMapper reachMapper,
                                PersonPort personPort, MerchantQueryPort merchantPort,
                                AuditLogPort auditLogPort,
                                ai.neargo.shop.user.service.PhoneCrypto phoneCrypto,
                                ai.neargo.shop.user.mapper.UserMappers.PersonMapper personMapper) {
        this.memberMapper = memberMapper;
        this.reachMapper = reachMapper;
        this.personPort = personPort;
        this.merchantPort = merchantPort;
        this.auditLogPort = auditLogPort;
        this.phoneCrypto = phoneCrypto;
        this.personMapper = personMapper;
    }

    @Override
    public PageData<OpsMemberVO> members(String entityNo, String phoneTail, long page, long size) {
        long pageNo = Math.max(page, 1);
        long pageSize = size <= 0 ? 20 : Math.min(size, 100);
        return DataScopeContext.executeWithoutScope(() -> {
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
        });
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
        return DataScopeContext.executeWithoutScope(() -> {
            var person = personMapper.selectOne(
                    Wrappers.<ai.neargo.shop.user.entity.UsrPerson>lambdaQuery()
                            .eq(ai.neargo.shop.user.entity.UsrPerson::getPersonNo, personNo)
                            .last("limit 1"));
            if (person == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            String phone = phoneCrypto.decrypt(person.getPhoneEnc());
            if (phone == null || phone.isBlank()) {
                // 密文解不开：多半是这条记录建于密钥配置之前。**明说**，别返回一个空串
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            /*
             * **先写审计再返回**。反过来的话，写审计失败时号码已经给出去了，
             * 而这条查看记录永远不存在 —— 事后追责会得出「没人看过」的结论。
             */
            auditLogPort.record("MEMBER_PHONE_REVEAL", personNo,
                    "查看完整手机号，理由：" + reason.trim());
            log.info("[member] {} 查看了人档 {} 的手机号，理由：{}", operatorNo, personNo, reason);
            return phone;
        });
    }

    @Override
    public List<ReachStatVO> reachStats(int days) {
        long since = System.currentTimeMillis() - Math.max(days, 1) * DAY;
        return DataScopeContext.executeWithoutScope(() -> {
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
        });
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
