package ai.neargo.shop.member.port;

import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.spi.member.MemberEventPort;
import org.springframework.stereotype.Component;

/**
 * {@link MemberEventPort} 的实现。只做转发 —— 幂等与分层判断都在 {@link MemberService} 里。
 */
@Component
public class MemberEventPortImpl implements MemberEventPort {

    private final MemberService memberService;

    public MemberEventPortImpl(MemberService memberService) {
        this.memberService = memberService;
    }

    @Override
    public int onPersonBound(String personNo) {
        return memberService.claimByPerson(personNo);
    }

    @Override
    public void onOrderPaid(OrderPaid e) {
        memberService.onOrderPaid(e.subOrderNo(), e.userNo(), e.personNo(),
                e.entityNo(), e.storeNo(), e.amountMinor(), e.paidAt());
    }
}
