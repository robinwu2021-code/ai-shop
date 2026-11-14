# Task55A 独立spec/quality审查约束
实际实现BASE为2bf892b98225e6f71bee2921df5e4935f5494119，HEAD/package由dispatch给出。
先读task-55a-brief.md，再读实现报告task-55a-report.md、supplied review package；完整设计task-55-design.md是精确合同附件，可按本步需要定向参考，不读全量主TDD或历史ledger。

本次只验收55A身份引用/临客消费/回执/本人归属/到店码与V5；55B签到重排、55C工单、55D直客恢复、59公开wire、60真实部署另有依赖关口。API/Java JSON中原160项仍是目标，不等于已实现。未知031无本域operation，不存contact/name低熵摘要；其能力异常分类必须符合brief。

## 全局绑定约束（从主TDD§1原文）
1. 严格沿[统一Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)及零售现有约定：资源Controller、Req/VO、Service/Impl分包、Impl直接调本域Mapper、BeautyMappers嵌套接口、BaseEntity/PageData/MybatisPages、统一ErrorCode/BizException/三语；不建Repository或BeautyApiResult/BeautyAuth等同义层。
2. 独立工程只拥有bty事实，Mch/商品/订单/Pay/库存实体和Mapper不得导入美业业务实现。同库不提供跨域直写许可；缺SPI在权威所有者补，不能先放beauty-core让餐饮依赖美业。
3. 人员可没有登录账号；业务人员、操作账号、顾客、买家、付款人分开。任职、业务岗位、资格、班表与系统权限分别校验；同主体同一STAFF跨店共用规范资源。
4. 请求主体/操作身份来自可信上下文，顾客接口校验本人归属；平台恢复不代授权/代完工。资源或Query里的personnelNo不是访问权限，跨店busy不返回他店顾客/任务/门店引用。
5. 迁移、类型、索引与默认值从当前设计真源评审生成；已执行版本冻结，后续只追加。不执行clean/repair/任意baseline；发现历史bty结构或资产账冲突先核对归属和历史，禁止IF NOT EXISTS掩盖漂移。
6. 本域短事务完成状态/版本/事实/outbox；跨资源/Core/Pay采用持久意图和原号恢复。HTTP不在长锁事务内等待，UNKNOWN不重新生成来源/支付号。
7. SQL CAS显式校验可信范围、版本/前态/数量上限并检查影响行；标量自定义SQL显式写version与updatedBy/updatedAt。空行查询不算稳定锁；首次创建靠唯一键与幂等协议。
8. 预约指定、确认安排、实际参与独立保存。服务完成和履约确认分开，重复回调不再增加doneQty或扣资产；工单关闭冻结范围后校验真实任务/绑定版本。
9. 日历采用不可变窗口快照和短轮询，分页齐全/投影延迟分别标记。日历空白不是可约承诺，真正容量仲裁在共用所有者；护理正文、照片和余额不混入日历/公共打印/外部通知。
10. 当期只生成需要的类/DTO和Mapper，不一次创建44个空Controller、25个Entity和160个DTO壳。后续源码变动先对账方法/API/字段真源再重建文档。

## 证据与报告
独立给出Spec PASS/FAIL及Quality Approved/Needs fixes，完整收齐Critical/Important/Minor及无法核验项；不要只给单项结论。新任务是native/真实host消费链，不把受控coreowner fixture叫真实owner容量验证。
基线守卫存在三项已定位失败：OpsPoints FQN、历史pay_ent_exception_action解析、BM03 JobLockConfiguration顶层包未登记（本分支欠账，Task60必须清理）。评审以实际变动和影响判断，本次批准不代表全平台/整个BM04通过。
报告要检查实际测试日志与命令/次数、RED/GREEN/消融和V5 frozen SHA，而不只复述自报。不重跑相同code的已有套件/容器；如发现具体证据不足请列明，主控负责补齐。不得修改repo/HEAD/index或spawn agents。完整报告写/tmp/review-bm04-55a.md，最后只返回双verdict/发现数/报告路径。
