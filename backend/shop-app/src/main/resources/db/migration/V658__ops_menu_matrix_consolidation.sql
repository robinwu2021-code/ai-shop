-- 运营端菜单权限矩阵-优化版（2026-11-11）：16 个一级合并为 13 个。
-- 会员管理、积分运营并入会员营销；消息客服并入内容客服；经营范围四项迁商品资料；准入押金迁经营财务；
-- 售后治理下重复的「退款分账」撤掉（授权先并到经营财务同名点）。
-- 只改名称、分组、排序与归属；不改 perm_code / 门禁 / 就绪度，不新增也不收回任何角色的可见功能。

-- 1) 一级分区
UPDATE sys_function SET name='工作台',sort=10,updated_at=NOW() WHERE function_code='OPS_DASHBOARD' AND end_code='OPS';
UPDATE sys_function SET name='商户中心',sort=20,updated_at=NOW() WHERE function_code='OPS_MERCHANT' AND end_code='OPS';
UPDATE sys_function SET name='商品资料',sort=30,updated_at=NOW() WHERE function_code='OPS_PRODUCT' AND end_code='OPS';
UPDATE sys_function SET name='经营服务',sort=40,updated_at=NOW() WHERE function_code='OPS_COMMERCE' AND end_code='OPS';
UPDATE sys_function SET name='进销存',sort=50,updated_at=NOW() WHERE function_code='OPS_INVENTORY' AND end_code='OPS';
UPDATE sys_function SET name='订单履约',sort=60,updated_at=NOW() WHERE function_code='OPS_ORDER' AND end_code='OPS';
UPDATE sys_function SET name='售后治理',sort=70,updated_at=NOW() WHERE function_code='OPS_AFTERSALE' AND end_code='OPS';
UPDATE sys_function SET name='会员营销',sort=80,updated_at=NOW() WHERE function_code='OPS_MARKETING' AND end_code='OPS';
UPDATE sys_function SET name='经营财务',sort=90,updated_at=NOW() WHERE function_code='OPS_FINANCE' AND end_code='OPS';
UPDATE sys_function SET name='内容客服',sort=100,updated_at=NOW() WHERE function_code='OPS_REVIEW' AND end_code='OPS';
UPDATE sys_function SET name='社区网点',sort=110,updated_at=NOW() WHERE function_code='OPS_COMMUNITY' AND end_code='OPS';
UPDATE sys_function SET name='风控',sort=120,updated_at=NOW() WHERE function_code='OPS_RISK' AND end_code='OPS';
UPDATE sys_function SET name='系统设置',sort=130,updated_at=NOW() WHERE function_code='OPS_IAM' AND end_code='OPS';

-- 2) 页面内操作点按权限前缀归属（同名分区优先，见 gen-perm-seed.mjs prefixToFn）
UPDATE sys_function_point SET function_code='OPS_PRODUCT',updated_at=NOW() WHERE point_code='ACT__CATEGORY_MANAGE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_CATALOG_EDIT' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_CATALOG_PUBLISH' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_COLLECTION_PROFILE_WRITE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_MALL_OFFLINE_CONFIRM' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_MALL_OFFLINE_EVIDENCE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_MALL_OFFLINE_REFUND_CONFIRM' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_MALL_OFFLINE_VOID' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_MALL_SHIPMENT_WRITE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_REFUND_REVIEW' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_SUBSCRIPTION_GRANT' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',updated_at=NOW() WHERE point_code='ACT__COMMERCE_SUBSCRIPTION_REVOKE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_FINANCE',updated_at=NOW() WHERE point_code='ACT__FINANCE_INVOICE_VERIFY' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_FINANCE',updated_at=NOW() WHERE point_code='ACT__FINANCE_RATE_READ' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_FINANCE',updated_at=NOW() WHERE point_code='ACT__FINANCE_RECON_RESOLVE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MARKETING',updated_at=NOW() WHERE point_code='ACT__MEMBER_PERSON_MERGE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MARKETING',updated_at=NOW() WHERE point_code='ACT__MEMBER_PHONE_REVEAL' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_ADMISSION_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_FULFILLMENT_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_MERCHANT_NUDGE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_MERCHANT_PAY_APPLY' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_MODE_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',updated_at=NOW() WHERE point_code='ACT__MERCHANT_STORE_NEARPAY_SYNC' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_REVIEW',updated_at=NOW() WHERE point_code='ACT__MESSAGE_TEMPLATE_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_REVIEW',updated_at=NOW() WHERE point_code='ACT__MESSAGE_TICKET_HANDLE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',updated_at=NOW() WHERE point_code='ACT__PRODUCT_CATEGORY_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',updated_at=NOW() WHERE point_code='ACT__PRODUCT_SPEC_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',updated_at=NOW() WHERE point_code='ACT__PRODUCT_STD_UPDATE' AND point_type='ACTION';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',updated_at=NOW() WHERE point_code='ACT__PRODUCT_TOPIC_UPDATE' AND point_type='ACTION';

-- 3) 菜单叶子：按冻结 point_code 定位
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='入驻审核',group_name='入驻',sort=10,updated_at=NOW() WHERE point_code='OPS_MERCHANT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='代办入驻',group_name='入驻',sort=20,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_ON_BEHALF' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='自营建档',group_name='入驻',sort=30,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_SELF_OPERATED' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='进件看板',group_name='入驻',sort=40,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_ONBOARDING' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='商家档案',group_name='档案',sort=50,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_LIST' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='门店档案',group_name='档案',sort=60,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_STORES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='资质档案',group_name='档案',sort=70,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_QUALIFICATIONS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='类目授权',group_name='档案',sort=80,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_CATEGORIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='认证管理',group_name='档案',sort=90,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_VERIFY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='自营风险',group_name='档案',sort=100,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_MODE_RISK' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='信用档案',group_name='信用',sort=110,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_CREDIT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='违规处置',group_name='信用',sort=120,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_BAN' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='链条画像',group_name='诊断',sort=130,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_CHAIN' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='店招审核',group_name='展示获客',sort=140,updated_at=NOW() WHERE point_code='OPS_STORE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='主页模板',group_name='展示获客',sort=150,updated_at=NOW() WHERE point_code='OPS_STORE__TAB_TEMPLATE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='门店码',group_name='展示获客',sort=160,updated_at=NOW() WHERE point_code='OPS_STORE__TAB_QRCODE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MERCHANT',name='获客效果',group_name='展示获客',sort=170,updated_at=NOW() WHERE point_code='OPS_STORE__TAB_EFFECT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='商品分类',group_name='基础资料',sort=10,updated_at=NOW() WHERE point_code='OPS_PRODUCT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='规格模板',group_name='基础资料',sort=20,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_TEMPLATES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='通用规格',group_name='基础资料',sort=30,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_SPEC_COMMON' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='专用规格',group_name='基础资料',sort=40,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_SPEC_SPECIAL' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='类目规格',group_name='基础资料',sort=50,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_CATEGORY_SPEC' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='标准品库',group_name='基础资料',sort=60,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_SPU_STD' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='主题分类',group_name='基础资料',sort=70,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_TOPICS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='建品规则',group_name='基础资料',sort=80,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_BANNED_WORD' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='限付类目',group_name='基础资料',sort=90,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_CATEGORY_PAY_MODE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='门店行业',group_name='经营范围',sort=100,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_STOREINDUSTRY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='行业准入',group_name='经营范围',sort=110,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_INDUSTRY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='经营授权',group_name='经营范围',sort=120,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_AUTHCODE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='范围开关',group_name='经营范围',sort=130,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_SCOPE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='商品管理',group_name='商品',sort=140,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_SKUS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='商品审核',group_name='商品',sort=150,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_AUDIT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='预售额度',group_name='商品',sort=160,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_STOCK' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_PRODUCT',name='商品统计',group_name='商品',sort=170,updated_at=NOW() WHERE point_code='OPS_PRODUCT__TAB_STATS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='套餐授予',group_name='主体套餐',sort=10,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_PLANS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='档位定义',group_name='主体套餐',sort=20,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_PLAN_DEFS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='套餐管理',group_name='门店套餐',sort=30,updated_at=NOW() WHERE point_code='OPS_COMMERCE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='服务分类',group_name='门店套餐',sort=40,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_CATEGORIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='服务商品',group_name='门店套餐',sort=50,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_PRODUCTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='购买订单',group_name='门店套餐',sort=60,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_ORDERS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='线下核款',group_name='门店套餐',sort=70,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_CLAIMS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='收款配置',group_name='门店套餐',sort=80,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_COLLECTION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='运营代购',group_name='门店套餐',sort=90,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_ASSISTED' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='订阅权益',group_name='门店套餐',sort=100,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_SOURCES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_INVENTORY',name='库存概览',group_name='库存治理',sort=10,updated_at=NOW() WHERE point_code='OPS_INVENTORY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_INVENTORY',name='库存流水',group_name='库存治理',sort=20,updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_LEDGER' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_INVENTORY',name='库存对差',group_name='切换判据',sort=30,updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_RECON' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_INVENTORY',name='链路健康',group_name='链路',sort=40,updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_LINK_HEALTH' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_INVENTORY',name='开放对接',group_name='对外',sort=50,updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_CREDENTIALS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='订单检索',group_name='订单',sort=10,updated_at=NOW() WHERE point_code='OPS_ORDER' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='异常订单',group_name='订单',sort=20,updated_at=NOW() WHERE point_code='OPS_ORDER__TAB_EXCEPTION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='代客下单',group_name='订单',sort=30,updated_at=NOW() WHERE point_code='OPS_ORDER__TAB_PROXY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='收款核对',group_name='支付',sort=40,updated_at=NOW() WHERE point_code='OPS_ORDER__TAB_PAY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='掉单补偿',group_name='支付',sort=50,updated_at=NOW() WHERE point_code='OPS_ORDER__TAB_REPAIR' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='关单规则',group_name='支付',sort=60,updated_at=NOW() WHERE point_code='OPS_ORDER__TAB_CLOSE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='批次配车',group_name='到货分拣',sort=70,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='自提分拣',group_name='到货分拣',sort=80,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_SORTING' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='核销监控',group_name='核销',sort=90,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_REDEEM' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='逾期规则',group_name='核销',sort=100,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_OVERDUE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='物流轨迹',group_name='物流',sort=110,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_EXPRESS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='运费模板',group_name='物流',sort=120,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_FREIGHT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_ORDER',name='运力配置',group_name='物流',sort=130,updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_CARRIER' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_AFTERSALE',name='售后工单',group_name='处置',sort=10,updated_at=NOW() WHERE point_code='OPS_AFTERSALE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_AFTERSALE',name='平台裁决',group_name='处置',sort=20,updated_at=NOW() WHERE point_code='OPS_AFTERSALE__TAB_INTERVENE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_AFTERSALE',name='售后规则',group_name='规则',sort=30,updated_at=NOW() WHERE point_code='OPS_AFTERSALE__TAB_FASTREFUND' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='券模板',group_name='优惠券',sort=10,updated_at=NOW() WHERE point_code='OPS_MARKETING' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='发放记录',group_name='优惠券',sort=20,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_ISSUES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='券敞口',group_name='优惠券',sort=30,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_PROMOCOUPONS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='活动',group_name='活动',sort=40,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_CAMPAIGNS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='活动敞口',group_name='活动',sort=50,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_PROMOACTIVITIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='平台活动',group_name='活动',sort=60,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_PLATFORM' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='报名审核',group_name='活动',sort=70,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_PLATFORMAUDIT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='首页推荐',group_name='活动',sort=80,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_SLOTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='营销广播',group_name='活动',sort=90,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_BROADCAST' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='商家团',group_name='拼团',sort=100,updated_at=NOW() WHERE point_code='OPS_GROUP' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='需求指派',group_name='拼团',sort=110,updated_at=NOW() WHERE point_code='OPS_GROUP__TAB_DEMANDS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='改价记录',group_name='拼团',sort=120,updated_at=NOW() WHERE point_code='OPS_GROUP__TAB_QUOTES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='归因规则',group_name='增长',sort=130,updated_at=NOW() WHERE point_code='OPS_GROWTH' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='归因审计',group_name='增长',sort=140,updated_at=NOW() WHERE point_code='OPS_GROWTH__TAB_TRACES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='邀请有礼',group_name='增长',sort=150,updated_at=NOW() WHERE point_code='OPS_GROWTH__TAB_FISSION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='会员名单',group_name='会员',sort=160,updated_at=NOW() WHERE point_code='OPS_MEMBER' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='人档',group_name='会员',sort=170,updated_at=NOW() WHERE point_code='OPS_MEMBER__TAB_PERSONS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='触达概览',group_name='会员',sort=180,updated_at=NOW() WHERE point_code='OPS_MEMBER__TAB_REACH' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='会员权益',group_name='会员',sort=190,updated_at=NOW() WHERE point_code='OPS_MARKETING__TAB_MEMBER' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='积分设置',group_name='积分',sort=200,updated_at=NOW() WHERE point_code='OPS_POINTS__TAB_CONFIGURATION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='赠送规则',group_name='积分',sort=210,updated_at=NOW() WHERE point_code='OPS_POINTS__TAB_EARNING_RULES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='平台预算',group_name='积分',sort=220,updated_at=NOW() WHERE point_code='OPS_POINTS__TAB_TREASURY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='积分账户',group_name='积分',sort=230,updated_at=NOW() WHERE point_code='OPS_POINTS__TAB_QUERIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_MARKETING',name='发放核销',group_name='积分',sort=240,updated_at=NOW() WHERE point_code='OPS_POINTS__TAB_SETTLEMENTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='结算分账',group_name='分账结算',sort=10,updated_at=NOW() WHERE point_code='OPS_FINANCE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='分账明细',group_name='分账结算',sort=20,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_SPLITS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='经营统计',group_name='分账结算',sort=30,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_SETTLE_STATS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='退款分账',group_name='分账结算',sort=40,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_REFUND_BACK' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='批次放款',group_name='分账结算',sort=50,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_SETTLE_BATCHES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='商家欠款',group_name='分账结算',sort=60,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_DEBTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='自营应付',group_name='应付发票',sort=70,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_PAYABLES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='供应账户',group_name='应付发票',sort=80,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_PAYOUT_ACCOUNTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='进项票',group_name='应付发票',sort=90,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_PURCHASE_INVOICES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='开票申请',group_name='应付发票',sort=100,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_BUYER_INVOICES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='发票个税',group_name='应付发票',sort=110,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_INVOICE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='服务费率',group_name='费率对账',sort=120,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_RATES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='渠道费率',group_name='费率对账',sort=130,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_PAY_CHANNELS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='渠道报文',group_name='费率对账',sort=140,updated_at=NOW() WHERE point_code='OPS_FINANCE__TAB_CHANNEL_MESSAGES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='准入押金',group_name='保证金',sort=150,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_ADMISSION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='支付单',group_name='支付运营',sort=160,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_ORDERS','OPS_PAY_01') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='支付异常',group_name='支付运营',sort=170,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_EXCEPTIONS','OPS_PAY_04') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='退款复核',group_name='支付运营',sort=180,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_REFUNDS','OPS_PAY_02') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='业务通知',group_name='支付运营',sort=190,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_NOTIFIES','OPS_PAY_03') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='请求记录',group_name='支付运营',sort=200,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_REQUEST_LOGS','OPS_PAY_06') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='收款码',group_name='支付运营',sort=210,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_QR_CODES','OPS_PAY_07') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='应用授权',group_name='支付运营',sort=220,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_APPS','OPS_PAY_05') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='平台密钥',group_name='支付运营',sort=230,updated_at=NOW() WHERE point_code IN ('OPS_FINANCE__PAY_PLATFORM_KEYS','OPS_PAY_08') AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='评价审核',group_name='评价',sort=10,updated_at=NOW() WHERE point_code='OPS_REVIEW' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='评价申诉',group_name='评价',sort=20,updated_at=NOW() WHERE point_code='OPS_REVIEW__TAB_APPEALS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='评分参数',group_name='评价',sort=30,updated_at=NOW() WHERE point_code='OPS_REVIEW__TAB_SCORE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='素材管理',group_name='素材',sort=40,updated_at=NOW() WHERE point_code='OPS_CONTENT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='内容审核',group_name='素材',sort=50,updated_at=NOW() WHERE point_code='OPS_CONTENT__TAB_AUDIT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='榜单问答',group_name='素材',sort=60,updated_at=NOW() WHERE point_code='OPS_CONTENT__TAB_RANK' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='通道总览',group_name='触达',sort=70,updated_at=NOW() WHERE point_code='OPS_MESSAGE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='短信',group_name='触达',sort=80,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_SMS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='邮件',group_name='触达',sort=90,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_MAIL' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='微信订阅',group_name='触达',sort=100,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_WXSUB' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='应用推送',group_name='触达',sort=110,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_APPPUSH' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='站内模板',group_name='触达',sort=120,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_INAPP' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='场景通道',group_name='触达',sort=130,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_ROUTING' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='发送记录',group_name='触达',sort=140,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_NOTIFYLOG' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='客服工单',group_name='客服',sort=150,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_TICKETS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_REVIEW',name='帮助中心',group_name='客服',sort=160,updated_at=NOW() WHERE point_code='OPS_MESSAGE__TAB_FAQ' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='社区网格',group_name='社区网格',sort=10,updated_at=NOW() WHERE point_code='OPS_COMMUNITY' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='商家提报',group_name='社区网格',sort=20,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_APPLIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='区划维护',group_name='社区网格',sort=30,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_REGIONS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='坐标健康',group_name='社区网格',sort=40,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_HEALTH' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='位置分布',group_name='社区网格',sort=50,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_DISTRIBUTION' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='地址库',group_name='社区网格',sort=60,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_PLACES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='自提点',group_name='自提点',sort=70,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_PICKUPS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMUNITY',name='临时点',group_name='自提点',sort=80,updated_at=NOW() WHERE point_code='OPS_COMMUNITY__TAB_NEIGHBOR' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_RISK',name='风险事件',group_name='识别',sort=10,updated_at=NOW() WHERE point_code='OPS_RISK' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_RISK',name='名单申诉',group_name='处置',sort=20,updated_at=NOW() WHERE point_code='OPS_RISK__TAB_BLACKLIST' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_RISK',name='拦截规则',group_name='处置',sort=30,updated_at=NOW() WHERE point_code='OPS_RISK__TAB_RULES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='运营账号',group_name='账号',sort=10,updated_at=NOW() WHERE point_code='OPS_IAM' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='角色权限',group_name='账号',sort=20,updated_at=NOW() WHERE point_code='OPS_IAM__TAB_ROLES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='菜单顺序',group_name='账号',sort=30,updated_at=NOW() WHERE point_code='OPS_IAM__TAB_MENU' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='审计日志',group_name='审计',sort=40,updated_at=NOW() WHERE point_code='OPS_IAM__TAB_AUDIT' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='打印运营',group_name='打印运营',sort=50,updated_at=NOW() WHERE point_code='OPS_PRINTING' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='任务日志',group_name='定时任务',sort=60,updated_at=NOW() WHERE point_code='OPS_JOBS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='外观文案',group_name='外观语言',sort=70,updated_at=NOW() WHERE point_code='OPS_SYSTEM' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='市场货币',group_name='外观语言',sort=80,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_MARKET' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='开关灰度',group_name='运行配置',sort=90,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_FLAGS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='存储管理',group_name='运行配置',sort=100,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_STORAGE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='测试账号',group_name='运行配置',sort=110,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_TESTPHONE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_IAM',name='应用版本',group_name='运行配置',sort=120,updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_APPVERSION' AND point_type='MENU';

-- 4) 并入后清空的三个一级：漏网的旧点（如历史积分页签）一并迁走，再停用空分区
UPDATE sys_function_point SET function_code='OPS_MARKETING',updated_at=NOW() WHERE function_code IN ('OPS_MEMBER','OPS_POINTS');
UPDATE sys_function_point SET function_code='OPS_REVIEW',updated_at=NOW() WHERE function_code='OPS_MESSAGE';
UPDATE sys_function SET enabled=0,updated_at=NOW() WHERE function_code IN ('OPS_MEMBER','OPS_POINTS','OPS_MESSAGE') AND end_code='OPS';

-- 5) 售后治理/退款分账与经营财务/退款分账是同一页同一码（finance:settle:execute）。
--    先把只挂在售后点上的授权（含运营自建角色）并到财务点，再撤售后点 —— 任何角色都不丢入口。
INSERT INTO sys_role_point (role_code, point_code, end_code, entity_no, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT a.role_code, 'OPS_FINANCE__TAB_REFUND_BACK', a.end_code, a.entity_no, a.tenant_no, NOW(), 'V658', NOW(), 'V658', 0, 0
FROM sys_role_point a
WHERE a.point_code = 'OPS_AFTERSALE__TAB_REFUND_BACK' AND a.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT role_code, entity_no FROM sys_role_point
                                 WHERE point_code = 'OPS_FINANCE__TAB_REFUND_BACK') f
                  WHERE f.role_code = a.role_code
                    AND (f.entity_no = a.entity_no OR (f.entity_no IS NULL AND a.entity_no IS NULL)));
DELETE FROM sys_role_point WHERE point_code = 'OPS_AFTERSALE__TAB_REFUND_BACK';
DELETE FROM sys_function_point WHERE point_code = 'OPS_AFTERSALE__TAB_REFUND_BACK';
