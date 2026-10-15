package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.MpSessionService;

import ai.neargo.auth.store.TokenHash;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.user.entity.UsrMpSession;
import ai.neargo.shop.user.mapper.UserMappers.MpSessionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/** Persist server-verified openid per token; works with every TokenStore, including DB identity reconstruction. */
@Service
public class MpSessionServiceImpl implements MpSessionService {
    private final MpSessionMapper mapper;

    public MpSessionServiceImpl(MpSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(String token, String userNo, String openid) {
        if (openid == null || openid.isBlank()) {
            return;
        }
        UsrMpSession row = new UsrMpSession();
        row.setTokenHash(TokenHash.of(token));
        row.setUserNo(userNo);
        row.setOpenid(openid);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }

    /** Never infer another account's payer identity; the token and subject must both match. */
    @Override
    public Optional<String> openid(String token, String userNo) {
        if (token == null || userNo == null) {
            return Optional.empty();
        }
        UsrMpSession row = mapper.selectById(TokenHash.of(token));
        return row != null && userNo.equals(row.getUserNo()) ? Optional.of(row.getOpenid()) : Optional.empty();
    }

    @Override
    public void removeUser(String userNo) {
        mapper.delete(Wrappers.<UsrMpSession>lambdaQuery()
                .eq(UsrMpSession::getUserNo, userNo));
    }

    @Override
    public void remove(String token) {
        mapper.deleteById(TokenHash.of(token));
    }
}
