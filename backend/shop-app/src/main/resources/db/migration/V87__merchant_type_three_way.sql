-- 商户类型三分：`MICRO` 不是法律形态，改名 `NATURAL_PERSON`；补上经营资格。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么必须改名
-- ─────────────────────────────────────────────────────────────────────────────
-- 「小微」在法规语境里是**已登记企业**的规模划型（小型微利企业，享所得税优惠），
-- 而这里指的是**没有营业执照的自然人**。两者唯一的共同点只有名字，含义相反。
--
-- 代价很具体：对财务或税务同事说「小微商户不能开票」，他听到的是一句错话
-- （小型微利企业当然能开票），而误会不会当场暴露。
--
-- 改成 NATURAL_PERSON 之后，三档正是税务系统对经营者的标准三分：
--   自然人 / 个体工商户 / 企业。
--
-- ⚠️ **`mch_payment_merchant.legal_form` 上的 MICRO 不动** —— 那是**通道进件档**
-- （微信小微），本来就是通道的概念，留在通道维度是对的。同名不同物，这次正是要把它们分开。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 存量：直接给正确取值，不悬置
-- ─────────────────────────────────────────────────────────────────────────────
-- 本项目尚未上生产，库里 144 家主体全是测试数据（已与产品确认可调整）。
-- 所以这里**不用 GRANDFATHERED 悬置人工核** —— 那套是为「生产存量身份不明」准备的。
--
-- 但默认值仍然写成最严的 UNREGISTERED：**新增主体默认什么都不能做**。
-- 存量在下面用显式 UPDATE 赋值，与「默认值恰好等于事实」是两回事 ——
-- V23 那次就是靠默认值回填存量，结果 54 张单的快照至今是假的。

ALTER TABLE mch_entity ADD COLUMN biz_qualification VARCHAR(16) NOT NULL DEFAULT 'UNREGISTERED'
    COMMENT '经营资格（轴①，法定）：REGISTERED 已登记 / EXEMPT 依法免登记 /
             UNREGISTERED 应登记未登记。**决定能不能交易，与通道无关**。
             默认最严 —— 没核过的主体不该自动获得交易能力';

ALTER TABLE mch_entity ADD COLUMN exempt_type VARCHAR(24) DEFAULT NULL
    COMMENT 'EXEMPT 时必填（电商法 §10 四类）：AGRI 自产农副产品 / HANDCRAFT 家庭手工业 /
             SERVICE 便民劳务 / PETTY 零星小额。
             **只有 PETTY 受 10 万元/年 约束**，其余三类无金额上限 ——
             混起来监控会误伤前三类';

-- ① 取值改名。mch_payment_merchant 不动（那是通道档）
UPDATE mch_entity       SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';
UPDATE mch_entity_apply SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';
UPDATE sys_legal_form
   SET legal_form = 'NATURAL_PERSON',
       name = '自然人',
       remark = '无营业执照的自然人经营者。**与法规「小微企业」无关** —— 那是有照企业的规模划型，重名且含义相反。受行业白名单限制（线上业态不收）；支付宝侧无对应档，故 alipay_code 留空'
 WHERE legal_form = 'MICRO';

-- 准入策略表按档位配置，档位改名要跟着改，否则策略匹配不上而**静默无限制**
UPDATE mch_admission_policy SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';

-- ② 存量经营资格
UPDATE mch_entity SET biz_qualification = 'REGISTERED'
 WHERE legal_form IN ('INDIVIDUAL', 'ENTERPRISE');

-- 自然人默认按「零星小额」豁免：测试数据里他们本来就是小额零散经营。
-- 用 PETTY 而不是 AGRI，是因为 PETTY 受 10 万元/年 约束 ——
-- **让监控链路有真实数据可跑**，而不是选一个天然不触发上限的类型把那条路径藏起来。
UPDATE mch_entity SET biz_qualification = 'EXEMPT', exempt_type = 'PETTY'
 WHERE legal_form = 'NATURAL_PERSON';

-- ③ 留两家农业生产者，让「无照 + 归集 + 收购发票」这条路径有数据可测。
-- 不留的话，那条花了整节论证的农户链路在开发库里一次都跑不到。
UPDATE mch_entity SET is_agri_producer = 1, exempt_type = 'AGRI'
 WHERE legal_form = 'NATURAL_PERSON'
 ORDER BY entity_no LIMIT 2;
