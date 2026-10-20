package ai.neargo.shop.member.mapper;

import ai.neargo.shop.member.entity.MbrIdentityTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Credential reads use explicit tenant and purpose predicates in the member owner service. */
public interface MemberIdentityTicketMapper extends BaseMapper<MbrIdentityTicket> { }
