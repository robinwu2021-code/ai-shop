package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.ChannelAccountVO;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 账号仅建DRAFT；授权由独立动作完成，公开请求不能直接设置ACTIVE。 */
public class ChannelAccountServiceImpl implements ChannelAccountService {
    private final AccountMapper accounts;
    private final ProductProfileMapper profiles;
    private final CommerceCommands commands;

    public ChannelAccountServiceImpl(
            AccountMapper accounts, ProductProfileMapper profiles, CommerceCommands commands) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.commands = commands;
    }

    @Override
    public ChannelAccountVO createAccount(
            String entity, String actor, CreateChannelAccountReq req, String key) {
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CommerceValidation.text(req.profileNo(), 32);
        CommerceValidation.text(req.externalAccountRef(), 128);
        return commands.execute(
                entity,
                null,
                actor,
                "CREATE_ACCOUNT",
                key,
                req,
                ChannelAccountVO.class,
                () -> {
                    var profile =
                            profiles.selectOne(
                                    Wrappers.<CimProductProfile>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("profile_no", req.profileNo()));
                    if (profile == null || "ARCHIVED".equals(profile.getStatus()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    var row = new CimAccount();
                    row.setTenantNo("MAIN");
                    row.setEntityNo(entity);
                    row.setAccountNo(BizKey.next("CIAC"));
                    row.setProfileNo(req.profileNo());
                    row.setExternalAccountRef(req.externalAccountRef());
                    row.setStatus("DRAFT");
                    row.setCreatedBy(actor);
                    row.setUpdatedBy(actor);
                    row.setVersion(0L);
                    row.setDeleted(0);
                    row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    row.setUpdatedAt(row.getCreatedAt());
                    accounts.insert(row);
                    return view(row);
                },
                ChannelAccountVO::accountNo);
    }

    @Override
    public ChannelAccountVO getAccount(String entity, String accountNo) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(accountNo, 32);
        return commands.query(() -> view(require(entity, accountNo)));
    }

    CimAccount require(String entity, String accountNo) {
        var row =
                accounts.selectOne(
                        Wrappers.<CimAccount>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("account_no", accountNo));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    @Override
    public PageData<ChannelAccountVO> listAccounts(String entity, int page, int size) {
        CommerceValidation.text(entity, 32);
        long offset = CommerceValidation.offset(page, size);
        return commands.query(
                () -> {
                    var query =
                            Wrappers.<CimAccount>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity);
                    long total = accounts.selectCount(query);
                    var records =
                            accounts
                                    .selectList(
                                            query.orderByDesc("created_at", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(ChannelAccountServiceImpl::view)
                                    .toList();
                    return PageData.of(records, total, page, size);
                });
    }

    private static ChannelAccountVO view(CimAccount row) {
        return new ChannelAccountVO(
                row.getAccountNo(),
                row.getProfileNo(),
                row.getExternalAccountRef(),
                row.getStatus(),
                row.getVersion());
    }
}
