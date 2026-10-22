# CAT-02B 餐牌时段与例外日

> 档位1；已实现并验收。依据完整需求§12 E01、增强方案P2、ADR040；承接CAT-02A，沿用户继续授权在codex/food-next01本人执行。

## §0 需求对账

| AC | 模块/API/表 |
|---|---|
| 早餐午晚及自定义、多周时段、跨午夜 | API017的SCHEDULE分段；MenuService.replaceSchedule；原fnb_menu_schedule |
| 指定日OPEN替代当日起始周规则，CLOSED关闭当日起始规则 | 原fnb_menu_exception；唯一日期；例外优先 |
| 适用日期范围及左闭右开 | 原fnb_menu.valid_from/to；MenuSchedulePolicy |
| 不扩大现有门店营业时间/接单日历 | MenuSchedulePolicy.acceptsAt调用原ServiceCalendarPolicy.acceptsAt；门店时区/日切复用当前策略 |
| 原子修改/版本/重放/历史不被改写 | 原MenuServiceImpl、Idempotency/审计/Outbox；只改工作行，保留原发布指针 |
| 统一架构与双部署 | 原BizMenuController/InternalMenuController/MenuInternalApi/RemoteMenuService；DataScope登记四张餐牌表 |

## §1 合同与简单规则

PUT /biz/x/food/menus/{menuNo}/definition，本批仅支持显式section="SCHEDULE"。输入ReplaceMenuScheduleReq={section,version:非负int64且小于Long.MAX_VALUE,weekSchedules:WeekScheduleInput[0..100],exceptions:ExceptionInput[0..366],validFrom?:YYYY-MM-DD,validTo?:YYYY-MM-DD}，Idempotency-Key必需。输出{menu:MenuVO,schedule:MenuScheduleVO}。API017完整门店/入口/菜项替换仍待后续；不接受未知字段或其它section，不把这一个分段标成整个API017已完成。

WeekScheduleInput={weekday:1..7,startTime:HH:mm,endTime:HH:mm,endDayOffset:0|1}。ExceptionInput={localDate:YYYY-MM-DD,mode:OPEN|CLOSED,startTime?:HH:mm,endTime?:HH:mm,endDayOffset:0|1}。CLOSED无时间且offset=0；OPEN时间必需。offset=0时end>start，offset=1时end<=start，区间长度(0,24小时]；周内及跨周重叠拒绝，相邻不重叠；每日期仅一条例外。日期1000..9999，起止日均含，from<=to；例外必须在已配置日期范围内。数组均必需，空数组显式清空。规范化按weekday/start及日期排序，幂等摘要不受数组次序影响。

MenuScheduleVO={validFrom?,validTo?,weekSchedules,exceptions}。原GET详情在{config:MenuVO}旁新增可空schedule；新提供方总返回实际规则，旧提供方缺字段表示未知，不能解释成已清空。列表/编辑头的旧回执不变化。新写回执必须有完整schedule并与规范化输入一致。

门店时区仍由当前生效策略确定，不另建营业时间。周规则和餐牌例外以本地开始日期为锚：周一22:00至周二02:00归周一规则；周一例外决定该段。早于日切的归属仍由原接单日历businessDate计算用于门店门控；不重新解释周时段为另一种时钟。评估至多检查本地今天和昨天两个起始日，左闭右开；菜单命中后还必须满足原门店状态、营业时间和接单日历。日切、已发布餐牌解析及正式可售没有第二套实现；本批策略暂供后续发布读取接线，不对外开放草稿可售。

更新仅允许授权单店、无override、DRAFT/PUBLISHED工作头，CAS成功后version/configVersion各加1，状态DRAFT；受控物理替换工作时段/例外，不触碰不可变历史。时区核验沿原策略；完成回执先重放，不受后来的策略变化影响。旧详情不需要经营策略在线。所有新子行由服务端生成业务号，不接受外部物理id。原权限、主体/当前店/actor、FOOD token沿用。

## §2 实施计划

Spec为§0/§1；用writing-plans将计划落本节，executing-plans本人顺序执行，test-driven-development先失败后实现；既有分支和共享工作区偏好保持。

- [x] 1. MenuSchedulePolicyTest先RED：精确结束、跨午夜/跨周、日期及例外优先、营业时间/日历求交、输入坏值/重叠；新增严格DTO/策略。Expected：规则GREEN且原ServiceCalendar不变。
- [x] 2. MenuScheduleServiceTest先RED：原Mapper落库/清空/读取、CAS/重放/共享/来源时区/Outbox回滚；四表受限DataScope测试先RED。实现原MenuServiceImpl扩展、两实体/嵌套Mapper、原装配及旧fixture对齐。Expected：真库行为、旧头接口兼容、V1~V5不变。
- [x] 3. 原MenuApiTest/RemoteMenuServiceTest新增分段及严格回执先RED，实现原Controller/typedSPI/Remote。Expected：权限/未知字段/缺集合/伪造回执拒绝，旧详情无schedule保持未知。
- [x] 4. 真源明确API017仅SCHEDULE，生成文档；原生HTTP测试读写规则、重放/清空/越店，完整构建、MySQL/MariaDB、三宿主、架构/设计/冻结/SQL检查；关键边界消融RED后恢复。唯一独立只读审查；Important一轮TDD修复，Minor登记。精确提交，保持他人修改。

Review Focus：跨周重叠、午夜例外起始日、日期边界；空集合真的清空且不触旧发布；共享编辑和主体隔离；旧详情缺新增字段的兼容；错误远端完整回执/规范化；公共DataScope对真实Mapper生效。

## §5 验收证据

| AC | 实际测试方法 |
|---|---|
| 精确结束/跨午夜/日期范围 | MenuSchedulePolicyTest.exactEndAndOvernightUseLocalStartDateAndDateRange |
| 例外替代与门店营业时间/营业日历求交 | exceptionReplacesAnchorDayAndCannotWidenStoreHoursOrBusinessCalendar |
| 跨周重叠及输入坏值 | rejectsOverlapIncludingSundayToMondayAndMalformedDefinitions |
| 规范化/相邻区间/空规则 | canonicalOrderingAdjacentIntervalsEmptyRulesAndStrictInput |
| 工作规则读写/清空/旧发布保留 | MenuScheduleServiceTest.replaceReadAndClearWorkingRulesKeepOldPublicationAndAllCounters |
| 幂等与CAS/时区策略变化 | completedReceiptSurvivesLaterEditsAndTimezoneChangesButNewWritesUseCas |
| 完整回滚与同键重试 | outboxFailureRestoresAllOldRowsDatesCountersAndKeyIsRetryable |
| 外店/共享头隔离 | foreignStoreAndSharedHeadAndInvalidRulesNeverMutateAnything |
| 公共数据范围真实生效 | registeredScopeProtectsAllFourRealMappersWithoutManualPredicates，实际RED读出OTHER→登记四表后GREEN |
| 公共权限/严格JSON分段 | MenuApiTest.scheduleSectionUsesExistingDefinitionRouteAndRejectsForeignFieldsAndMalformedTokens |
| 完整远程回执与旧详情未知 | RemoteMenuServiceTest.scheduleReceiptsMustContainExactDefinitionWhileLegacyDetailStaysUnknown |
| V1/实际token及typed HTTP | FoodRecipePrimaryDatabaseTest中的menusOverHttp扩展规则写入/读取/重放/清空/外店404 |

定义/策略缺类型编译RED后4项GREEN；Service缺方法/Mapper编译RED，行为测试4项先GREEN，DataScope一项实际RED后登记修复；全部核心15项实际通过。公共/typed接口缺Command/方法编译RED后8项实际通过。端点未知字段/集合缺失/严格身份类型拒绝，历史提供方缺schedule保留未知。

结束边界消融：仅把end exclusive改成inclusive，到11:00必须停止的断言实际RED；finally逐字节恢复实现后完整构建420总数、374实际通过、46条件跳过，零失败/错误。MySQL231项实际通过、无跳过（19+200核心守卫+8独立HTTP+4规格权威），包括四表范围及实际规则链路。

相关设计34/34、冻结V1/schema5与SQL兼容通过；全部food设计49项48通过，唯一失败仍为既有未跟踪模块清单76≠73。MariaDB、三宿主和独立审查最终结论随后追加。

## §6 类方法及实现对账

api.dto新增WeekScheduleInput/ExceptionInput/ReplaceMenuScheduleReq/MenuScheduleVO/MenuScheduleResultVO；api.policy.MenuScheduleDefinitions（纯定义校验）；core.policy.MenuSchedulePolicy（复用ServiceCalendarPolicy求交）；原MenuConfigResultVO可空新增字段、原MenuService/SPI增replaceSchedule。core.entity.FnbMenuSchedule/FnbMenuException及FoodMappers对应嵌套Mapper；原MenuServiceImpl拥有修改事务与详情读取；原FoodAutoConfiguration接线及FoodDataScopeRegistration四表登记。web/server原MenuController和Remote补分支，不新建Repository/修订体系。既有两表+原头日期字段，无新迁移。菜项/渠道/发布与整个P2仍未完成，Keeta仍仅规划。


§6补充对账：规范化移至food-api.policy.MenuScheduleDefinitions，运行时求交在food-core.policy.MenuSchedulePolicy，以免API反向依赖Core；原ServiceCalendarPolicy不改。MenuChanged增加可空schedule及保留旧构造器，时段修改的审计摘要/Outbox保存完整规范化规则，普通头变更保持旧调用。原MenuServiceImpl统一editable校验供头与时段两条写入复用；头编辑仍可将旧时区修复为当前策略，时段写入要求已有头时区一致。GET详情新增字段缺省保留未知，列表/头写旧回执不变。


## §7 最终验收与遗留

完整构建420总数、374实际通过、46条件跳过，零失败/错误；MySQL231项实际通过无跳过。MariaDB餐牌头6+时段事务5+策略4+实际HTTP1=16项实际通过无跳过。三宿主retail/embedded/standalone分别5/5/6实际通过，前两者各1项条件跳过。餐饮架构通过；原项目架构15/16，唯一失败为既有范围外OpsPoints全限定注解，未放宽守卫。

唯一fresh独立只读审查Critical0/Important0/Minor2；按实际影响复核后无阻断项，无Important修复轮次或二次审查。两项Minor登记后续：①受限DataScope下服务分页的EXISTS/计数缺直接回归；已核对拦截器实际字节码未发现别名缺陷，现有四表Mapper范围测试通过。②API005的needs能力说明仍写时段规则待后续，虽输出已含schedule、API017也已明确SCHEDULE能力；运行行为不受影响，后续统一能力说明。本批不把两项Minor混入审查修复范围。

本批取舍及代价：API017只支持显式SCHEDULE，完整门店/入口/菜项组成仍待后续；本地开始日期锚定餐牌规则、原营业日切点仅用于门店日历；旧提供方缺schedule表示未知，需要升级后才能完整显示。可执行策略尚未接入已发布餐牌解析/冲突/报价/库存/渠道，不能据此声称可营业。Keeta保持延期，无生产Outbox消费者/部署/经营库操作；容量和极端并发需上线前验证。旧recipe范围登记及未归属UI/库存草稿/原架构欠账保持独立；独立审查只读，测试门禁由实现者据真实日志确认。

三份设计文档已重新生成，只有API设计发生变化；目标82表、154目标API和当前迁移版本5不变。V1～V5字节未修改，无新增迁移。提交31个精确路径，原有工作区其他业务改动保留；整个P2及后续P3～P6仍未完成。下一步为餐牌分类、菜项/SKU编排及选配覆盖。

```text
backend-plugins/shop-industry-food/README.md
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ExceptionInput.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuConfigResultVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuScheduleResultVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuScheduleVO.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuScheduleReq.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/WeekScheduleInput.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuScheduleDefinitions.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/MenuService.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuInternalApi.java
backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChanged.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodAutoConfiguration.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodDataScopeRegistration.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FnbMenuException.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/entity/FnbMenuSchedule.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/mapper/FoodMappers.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/policy/MenuSchedulePolicy.java
backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/service/impl/MenuServiceImpl.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/MenuSchedulePolicyTest.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/MenuScheduleServiceTest.java
backend-plugins/shop-industry-food/food-core/src/test/java/ai/neargo/shop/food/scenario/MenuServiceTest.java
backend-plugins/shop-industry-food/food-server/src/main/java/ai/neargo/shop/food/server/api/InternalMenuController.java
backend-plugins/shop-industry-food/food-server/src/test/java/ai/neargo/shop/food/scenario/FoodRecipePrimaryDatabaseTest.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizMenuController.java
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/foodclient/RemoteMenuService.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/MenuApiTest.java
backend-plugins/shop-industry-food/food-web/src/test/java/ai/neargo/shop/food/scenario/RemoteMenuServiceTest.java
docs/technical/TDD-餐饮系统-CAT-02B-餐牌时段与例外日.md
docs/technical/design/schema/food-system-design.json
docs/technical/design/餐饮系统-API设计.md
scripts/verify-food-primary-mysql.py
```
