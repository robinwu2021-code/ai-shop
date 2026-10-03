-- 修正 sys_legal_form 的微信侧映射。**两处都是照着想当然写的，不是抄错**，
-- 而两处都只在真实进件时才会暴露 —— 报错是「主体类型与资料不符」，
-- 排查的人会先怀疑上传的资料，不会怀疑映射表。
--
-- 依据（2026-08-13 核对微信支付合作伙伴文档中心）：
--   · 特约商户进件 提交申请单 subject_type 的取值域只有五个：
--     SUBJECT_TYPE_INDIVIDUAL(个体户) / _ENTERPRISE(企业) / _GOVERNMENT(政府机关)
--     / _INSTITUTIONS(事业单位) / _OTHERS(社会组织)
--     文档原话：**本接口不支持进件个体小微商户**
--   · 小微走的是**另一个接口**（小微商户进件），subject_type 只能是 SUBJECT_TYPE_MICRO
--   · 小微的结算账户是 bank_account_type=BANK_ACCOUNT_TYPE_PERSONAL +
--     account_name/account_bank/account_number（**个人银行卡**）。
--     该接口里确实有 openid 字段，但那是**超管的 openid，用于签约确认**，不是结算去向。

-- ① 个体工商户的 wechat_code 是 'SMALL' —— 取值域里根本没有这个值。
--    本表的约定是「去掉 SUBJECT_TYPE_ 前缀」（企业那行的 'ENTERPRISE' 正是这么来的），
--    照此个体户应当是 'INDIVIDUAL'。
--    这条 SET 常量本身就幂等，不需要额外条件。
UPDATE sys_legal_form SET wechat_code = 'INDIVIDUAL' WHERE legal_form = 'INDIVIDUAL';

-- ② 旧值描述的机制不存在：钱不进微信零钱，进个人银行卡。
--    这个错误会一路传到 B 端 —— 入驻页对小微显示「微信零钱（个人）」，
--    商家据此以为不用提供银行卡，而进件时那三个字段是必填的。
--    ⚠️ 改名用全仓 sed 时**要排除本文件**：连这里一起替换，
--    下面这条就变成 A=A 的空转，而且不报错（已经踩过一次）。
UPDATE sys_legal_form SET settle_account_type = 'PERSONAL_BANK_CARD'
 WHERE settle_account_type = 'PERSONAL_OPENID';

-- ③ 「走哪个进件接口」此前无处表达，而它决定了调哪个 URL、传哪套字段。
--    暂记在 remark 里而不是新开一列：真正的网关还没写（收尾清单 C1），
--    此刻加一个没人读的列，就是又一次「闸门写好了但数据源没接」。
--    C1 联调时把它提升为字段。
--    用 CONCAT 追加而不是整段覆写：V87 那句「与法规『小微企业』无关」是
--    这一档最容易被误解的地方，覆掉等于把已经解释清楚的事又变回含糊。
--    但 CONCAT **不幂等** —— 靠 NOT LIKE 守住重跑（ADR-015 §3.1）。
UPDATE sys_legal_form
   SET remark = CONCAT(remark, ' ⚠️ 结算到【个人银行卡】（不是微信零钱）。走【小微商户进件】接口，与个体户/企业的【特约商户进件】不是同一个接口，subject_type 只能是 SUBJECT_TYPE_MICRO')
 WHERE legal_form = 'NATURAL_PERSON' AND remark NOT LIKE '%小微商户进件%';

UPDATE sys_legal_form
   SET remark = CONCAT(remark, '。走【特约商户进件】，subject_type=SUBJECT_TYPE_INDIVIDUAL')
 WHERE legal_form = 'INDIVIDUAL' AND remark NOT LIKE '%特约商户进件%';

UPDATE sys_legal_form
   SET remark = CONCAT(remark, '。走【特约商户进件】，subject_type=SUBJECT_TYPE_ENTERPRISE')
 WHERE legal_form = 'ENTERPRISE' AND remark NOT LIKE '%特约商户进件%';

-- 列注释同步 —— 注释里写着错的取值，下一个人会照着错的写
ALTER TABLE sys_legal_form
    MODIFY COLUMN settle_account_type VARCHAR(24) DEFAULT NULL
        COMMENT '结算账户形态：PERSONAL_BANK_CARD（打到个人银行卡）/ MERCHANT_ID（打到对公）',
    MODIFY COLUMN wechat_code VARCHAR(32) DEFAULT NULL
        COMMENT '微信 subject_type，去掉 SUBJECT_TYPE_ 前缀；为空表示微信不收这种主体';
