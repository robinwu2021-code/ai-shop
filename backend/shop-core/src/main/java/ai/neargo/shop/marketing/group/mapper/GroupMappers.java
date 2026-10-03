package ai.neargo.shop.marketing.group.mapper;

import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.entity.MktGroupMember;
import ai.neargo.shop.marketing.group.entity.MktQuote;
import ai.neargo.shop.marketing.group.entity.MktQuoteRevision;
import ai.neargo.shop.marketing.group.entity.MktRequest;
import ai.neargo.shop.marketing.group.entity.MktRequestInterest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 团购与求团的 Mapper 集合。 */
public final class GroupMappers {

    private GroupMappers() {
    }

    public interface GroupBuyMapper extends BaseMapper<MktGroupBuy> {
    }

    public interface GroupMemberMapper extends BaseMapper<MktGroupMember> {
    }

    public interface RequestMapper extends BaseMapper<MktRequest> {
    }

    public interface RequestInterestMapper extends BaseMapper<MktRequestInterest> {
    }

    public interface QuoteMapper extends BaseMapper<MktQuote> {
    }

    public interface QuoteRevisionMapper extends BaseMapper<MktQuoteRevision> {
    }
}
