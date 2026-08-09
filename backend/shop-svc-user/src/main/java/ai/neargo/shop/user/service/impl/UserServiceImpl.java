package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.OtpStore;
import ai.neargo.shop.user.service.UserService;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.community.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.PickupPointMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PickupPointMapper pickupMapper;
    private final OtpStore otpStore;

    public UserServiceImpl(UserMapper userMapper, PickupPointMapper pickupMapper, OtpStore otpStore) {
        this.userMapper = userMapper;
        this.pickupMapper = pickupMapper;
        this.otpStore = otpStore;
    }

    @Override
    public UserVO profile() {
        return UserVO.of(currentUser());
    }

    @Override
    @Transactional
    public UserVO bindCommunity(String communityNo, String pickupNo) {
        CmtPickupPoint pickup = pickupMapper.selectOne(Wrappers.<CmtPickupPoint>lambdaQuery()
                .eq(CmtPickupPoint::getPickupNo, pickupNo)
                .last("limit 1"));
        // 校验「自提点属于该社区」而不是只查存在性：端上可以随便传两个不相干的号，
        // 存进去之后商品池按社区取、履约按自提点走，用户会看到一个永远到不了货的组合
        if (pickup == null || !pickup.getCommunityNo().equals(communityNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        UsrAccount user = currentUser();
        user.setCommunityNo(communityNo);
        user.setPickupNo(pickupNo);
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    @Override
    @Transactional
    public UserVO updateProfile(String nickname, String avatar) {
        UsrAccount user = currentUser();
        // 传 null 表示「不改这个字段」，而不是「清空」——端上只提交改动的那个
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    @Override
    @Transactional
    public UserVO bindPhone(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        // 该手机号已属于另一个账号：这是账号合并问题，一期不做自动合并 ——
        // 自动合并要处理两边的订单、余额、优惠券归属，错一步就是资损
        UsrAccount owner = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getPhone, phone).last("limit 1"));
        UsrAccount user = currentUser();
        if (owner != null && !owner.getUserNo().equals(user.getUserNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        user.setPhone(phone);
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    private UsrAccount currentUser() {
        String userNo = SecurityUtils.currentUserNo();
        UsrAccount user = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
        if (user == null) {
            // 会话有效但用户没了（被硬删或换库）：当作未登录，让端上清 token 重登
            throw new ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException();
        }
        return user;
    }
}
