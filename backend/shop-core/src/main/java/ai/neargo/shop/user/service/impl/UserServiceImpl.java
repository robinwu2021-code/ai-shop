package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.service.UserService;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final IdentityMapper identityMapper;
    private final PickupQueryPort pickupQueryPort;
    private final OtpStore otpStore;

    public UserServiceImpl(UserMapper userMapper, IdentityMapper identityMapper,
                           PickupQueryPort pickupQueryPort, OtpStore otpStore) {
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.otpStore = otpStore;
    }

    @Override
    public UserVO profile() {
        return UserVO.of(currentUser());
    }

    @Override
    @Transactional
    public UserVO bindCommunity(String communityNo, String pickupNo) {
        // 校验「自提点属于该社区」而不是只查存在性：端上可以随便传两个不相干的号，
        // 存进去之后商品池按社区取、履约按自提点走，用户会看到一个永远到不了货的组合
        if (pickupQueryPort.find(pickupNo)
                .filter(p -> communityNo.equals(p.communityNo())).isEmpty()) {
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

        UsrAccount user = currentUser();

        /*
         * 该手机号已属于另一个账号：这是账号合并问题，一期不做自动合并 ——
         * 自动合并要迁移两边的订单、积分、卡包、优惠券、地址，横跨五个域，错一步就是资损
         * （安全整改方案 §6.7）。一期只做「检测 + 阻止 + 留痕」。
         *
         * 查的是 usr_identity 而不是 usr_account.phone：手机号是**唯一权威标识**（S2），
         * 权威表是凭证表。只查旧列的话，一个从 App 注册、手机号只登记在 usr_identity 上
         * 的账号会被漏掉，于是两个人拿到同一个手机号——正是这条检测要防的事。
         */
        UsrIdentity owner = identityMapper.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.PHONE)
                .eq(UsrIdentity::getIdentityValue, phone).last("limit 1"));
        if (owner != null) {
            if (!owner.getUserNo().equals(user.getUserNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return UserVO.of(user);   // 已经绑过同一个号，幂等返回
        }

        UsrIdentity row = new UsrIdentity();
        row.setUserNo(user.getUserNo());
        row.setIdentityType(IdentityType.PHONE);
        row.setIdentityValue(phone);
        row.setVerifiedAt(java.time.LocalDateTime.now());
        identityMapper.insert(row);

        // 过渡期双写旧列（V3 只加不删）。确认两边一致后随删列一起去掉
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
