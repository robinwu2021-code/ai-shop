# 餐饮DTO字段字典

基线：8c035162615dcebb2a4bc9ada07830d5e469f75b。Java record 字段为源码声明，非完整OpenAPI Schema；金额、单位、枚举和业务必填条件须结合服务规则。未列入的普通class、共享域及嵌套依赖通过接口源码定位，不把未知结构伪装为已定义模型。

## ai.neargo.shop.food.dto.ActionLogVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ActionLogVO.java)

### ActionLogVO

| 字段声明（含校验） |
| --- |
| <code>String actionNo</code> |
| <code>String action</code> |
| <code>String actorRef</code> |
| <code>Instant occurredAt</code> |
| <code>String reason</code> |

## ai.neargo.shop.food.dto.ArchiveTableReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ArchiveTableReq.java)

### ArchiveTableReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.AuthorizeCartSessionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/AuthorizeCartSessionReq.java)

### AuthorizeCartSessionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.BottleCustodyContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BottleCustodyContracts.java)

### Deposit

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String customerNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String unit</code> |
| <code>Instant expiresAt</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String originalReceiptRef</code> |

### Withdraw

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String action</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String recipientNo</code> |

### Custody

| 字段声明（含校验） |
| --- |
| <code>String custodyNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String customerNo</code> |
| <code>String skuNo</code> |
| <code>BigDecimal deposited</code> |
| <code>BigDecimal remaining</code> |
| <code>String unit</code> |
| <code>Instant expiresAt</code> |
| <code>String status</code> |
| <code>long version</code> |

### Movement

| 字段声明（含校验） |
| --- |
| <code>String movementNo</code> |
| <code>String custodyNo</code> |
| <code>String action</code> |
| <code>BigDecimal quantity</code> |
| <code>BigDecimal remaining</code> |
| <code>String unit</code> |
| <code>String actorNo</code> |
| <code>String recipientNo</code> |
| <code>String reason</code> |
| <code>Instant occurredAt</code> |

### Result

| 字段声明（含校验） |
| --- |
| <code>Custody custody</code> |
| <code>Movement movement</code> |

## ai.neargo.shop.food.dto.BundleChoiceInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleChoiceInput.java)

### BundleChoiceInput

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String choiceNo</code> |
| <code>@NotBlank @Size(max=32) String groupNo</code> |
| <code>@NotBlank @Size(max=32) String skuNo</code> |
| <code>@NotNull BigDecimal unitsPerPick</code> |
| <code>@NotBlank @Size(max=24) String priceMode</code> |
| <code>@NotNull BigDecimal defaultQty</code> |
| <code>@NotNull BigDecimal minQty</code> |
| <code>@NotNull BigDecimal maxQty</code> |
| <code>@NotNull @Min(0) Integer sortNo</code> |

## ai.neargo.shop.food.dto.BundleConfigVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleConfigVO.java)

### BundleConfigVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>String status</code> |
| <code>Data data</code> |

### Data

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion</code> |
| <code>String publishedRevisionNo</code> |
| <code>List<BundleGroupInput> groups</code> |
| <code>List<BundleChoiceInput> choices</code> |

## ai.neargo.shop.food.dto.BundleGroupInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleGroupInput.java)

### BundleGroupInput

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String groupNo</code> |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull BigDecimal minQty</code> |
| <code>@NotNull BigDecimal maxQty</code> |
| <code>@Size(max=32) String baseSkuNo</code> |
| <code>@NotNull @Min(0) Integer sortNo</code> |

## ai.neargo.shop.food.dto.BundleMutationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundleMutationVO.java)

### BundleMutationVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |

## ai.neargo.shop.food.dto.BundlePublicationSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BundlePublicationSnapshot.java)

### BundlePublicationSnapshot

| 字段声明（含校验） |
| --- |
| <code>int contentSchema</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion</code> |
| <code>BundleConfigVO bundle</code> |
| <code>List<SkuIdentityFact> skuFacts</code> |
| <code>String approvalRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext</code> |

## ai.neargo.shop.food.dto.BusinessCloseContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java)

### DayVO

| 字段声明（含校验） |
| --- |
| <code>String dayNo</code> |
| <code>LocalDate businessDate</code> |
| <code>String policyNo</code> |
| <code>LocalDateTime startsAt</code> |
| <code>LocalDateTime endsAt</code> |
| <code>String status</code> |
| <code>String currentCloseRefs</code> |
| <code>long version</code> |

### Issue

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>String sourceRef</code> |

### ReportVO

| 字段声明（含校验） |
| --- |
| <code>LocalDate businessDate</code> |
| <code>String currency</code> |
| <code>long batchCount</code> |
| <code>Map<String, BigDecimal> quantitiesByUnit</code> |
| <code>Map<String, BigDecimal> originalOrderServiceQuantitiesByActionUnit</code> |
| <code>String returnAndServiceScope</code> |
| <code>long settlementAttemptCount</code> |
| <code>long requestedDueMinor</code> |
| <code>long returnCount</code> |
| <code>String salesCompleteness</code> |
| <code>String settlementCompleteness</code> |
| <code>String refundCompleteness</code> |
| <code>List<Issue> issues</code> |
| <code>String sourceHash</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) List<OrderReceivable> originalOrderReceivables</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor</code> |

### OrderReceivable

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |
| <code>String currency</code> |
| <code>long originalMinor</code> |
| <code>long effectiveMinor</code> |
| <code>long adjustmentMinor</code> |
| <code>String amendmentSourceHash</code> |
| <code>String evidenceHash</code> |

### CloseVO

| 字段声明（含校验） |
| --- |
| <code>String closeNo</code> |
| <code>String dayNo</code> |
| <code>String currency</code> |
| <code>int revision</code> |
| <code>String status</code> |
| <code>String snapshotHash</code> |
| <code>String previousCloseNo</code> |
| <code>String signedBy</code> |
| <code>LocalDateTime signedAt</code> |
| <code>ReportVO report</code> |
| <code>long version</code> |

### PreviewReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull LocalDate businessDate</code> |
| <code>@NotBlank @Pattern(regexp = "[A-Z]{3}") String currency</code> |

### SignReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String closeNo</code> |
| <code>@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotHash</code> |

### ExceptionVO

| 字段声明（含校验） |
| --- |
| <code>String exceptionNo</code> |
| <code>String closeNo</code> |
| <code>String kind</code> |
| <code>String sourceRef</code> |
| <code>String status</code> |
| <code>String ownerNo</code> |
| <code>String nextDayNo</code> |
| <code>String reason</code> |
| <code>String proposedBy</code> |
| <code>String approvedBy</code> |
| <code>LocalDateTime approvedAt</code> |
| <code>String approvalRef</code> |
| <code>long version</code> |

### ProposeCarryReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max = 32) String ownerNo</code> |
| <code>@NotBlank @Size(max = 32) String nextDayNo</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### ApproveCarryReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |

### AdjustmentReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String originalCloseNo</code> |
| <code>@NotBlank @Size(max = 32) String currentDayNo</code> |
| <code>@NotBlank @Size(max = 64) String coreReference</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |
| <code>@NotBlank @Size(max = 64) String approvalRef</code> |

## ai.neargo.shop.food.dto.BusinessOperationsContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessOperationsContracts.java)

### Source

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String kind</code> |
| <code>@NotBlank String sourceNo</code> |

### Evidence

| 字段声明（含校验） |
| --- |
| <code>Source source</code> |
| <code>String status</code> |
| <code>long version</code> |
| <code>String sourceHash</code> |
| <code>boolean terminal</code> |
| <code>String currentOwner</code> |

### HandoverReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String receiverNo</code> |
| <code>@NotEmpty @Size(max = 100) List<@Valid Source> sources</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### HandoverVO

| 字段声明（含校验） |
| --- |
| <code>String handoverNo</code> |
| <code>String fromActor</code> |
| <code>String receiverNo</code> |
| <code>String status</code> |
| <code>String sourceHash</code> |
| <code>long version</code> |

### CaseReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Valid Source source</code> |
| <code>@NotBlank String ownerNo</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### CaseVO

| 字段声明（含校验） |
| --- |
| <code>String caseNo</code> |
| <code>Source source</code> |
| <code>String ownerNo</code> |
| <code>String status</code> |
| <code>String sourceHash</code> |
| <code>String resolutionHash</code> |
| <code>long version</code> |

### DecisionReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

## ai.neargo.shop.food.dto.BusinessReturnApprovalContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessReturnApprovalContracts.java)

### ApproveReq

| 字段声明（含校验） |
| --- |
| <code>Long version</code> |
| <code>String reason</code> |
| <code>String replacementCartNo</code> |

### ReplacementReq

| 字段声明（含校验） |
| --- |
| <code>Long version</code> |
| <code>String replacementBatchNo</code> |
| <code>String reason</code> |

### ApprovalVO

| 字段声明（含校验） |
| --- |
| <code>String approvalNo</code> |
| <code>String returnNo</code> |
| <code>long version</code> |
| <code>String status</code> |
| <code>boolean pending</code> |
| <code>boolean originalPayableChanged</code> |
| <code>Receipt originalAmendment</code> |
| <code>String replacementBatchNo</code> |
| <code>String replacementOrderNo</code> |

## ai.neargo.shop.food.dto.BusinessReturnContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessReturnContracts.java)

### Line

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String foodLineNo</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty</code> |
| <code>String componentNo</code> |

### CreateReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long batchVersion</code> |
| <code>@NotNull @Size(min = 1, max = 256) List<@Valid Line> lines</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### CancelReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### ComponentSource

| 字段声明（含校验） |
| --- |
| <code>String sourceComponentRef</code> |
| <code>String kind</code> |
| <code>String allocationNo</code> |
| <code>Long orderItemId</code> |
| <code>BigDecimal originalQty</code> |
| <code>String unit</code> |
| <code>String coreSnapshotHash</code> |
| <code>Long allocationVersion</code> |
| <code>String componentNo</code> |
| <code>Long componentVersion</code> |

### SourceItem

| 字段声明（含校验） |
| --- |
| <code>String foodLineNo</code> |
| <code>String sourceLineRef</code> |
| <code>String mainAllocationNo</code> |
| <code>Long mainOrderItemId</code> |
| <code>BigDecimal qty</code> |
| <code>BigDecimal originalQty</code> |
| <code>Long lineVersion</code> |
| <code>List<ComponentSource> components</code> |

### OwnerRead

| 字段声明（含校验） |
| --- |
| <code>String checkedAt</code> |
| <code>Result original</code> |

### Frozen

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>int schema</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String batchNo</code> |
| <code>Long batchVersion</code> |
| <code>String cartNo</code> |
| <code>Long cartVersion</code> |
| <code>String bindingNo</code> |
| <code>Long bindingVersion</code> |
| <code>String buyerNo</code> |
| <code>String actorNo</code> |
| <code>String reason</code> |
| <code>String sourceHash</code> |
| <code>OwnerRead ownerRead</code> |
| <code>List<SourceItem> items</code> |

### ItemVO

| 字段声明（含校验） |
| --- |
| <code>String returnItemNo</code> |
| <code>String foodLineNo</code> |
| <code>String sourceLineRef</code> |
| <code>BigDecimal qty</code> |
| <code>String kitchenDisposition</code> |
| <code>String mainAllocationNo</code> |

### ReturnVO

| 字段声明（含校验） |
| --- |
| <code>String returnNo</code> |
| <code>String batchNo</code> |
| <code>String storeNo</code> |
| <code>long version</code> |
| <code>String status</code> |
| <code>String financialStatus</code> |
| <code>String kitchenStatus</code> |
| <code>String coreCaseRef</code> |
| <code>boolean refundRequested</code> |
| <code>boolean originalPayableChanged</code> |
| <code>String reason</code> |
| <code>String actorNo</code> |
| <code>List<ItemVO> items</code> |
| <code>Frozen source</code> |

## ai.neargo.shop.food.dto.BusinessShareContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessShareContracts.java)

### Person

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String personNo</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor</code> |

### LineWeight

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String foodLineNo</code> |
| <code>@NotBlank @Size(max = 32) String personNo</code> |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long weight</code> |

### CreateReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long checkVersion</code> |
| <code>@NotBlank String mode</code> |
| <code>@NotNull @Size(min = 1, max = 50) List<@Valid Person> people</code> |
| <code>@Size(max = 1000) List<@Valid LineWeight> lineWeights</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### ConfirmReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank @Size(min = 64, max = 64) String sourceHash</code> |
| <code>@NotBlank @Size(min = 64, max = 64) String planHash</code> |

### CancelReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### MembershipScope

| 字段声明（含校验） |
| --- |
| <code>String membershipNo</code> |
| <code>String checkNo</code> |
| <code>String batchNo</code> |
| <code>Long version</code> |
| <code>LocalDateTime attachedAt</code> |
| <code>String operationNo</code> |

### BatchSource

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String originalCheckNo</code> |
| <code>Long version</code> |
| <code>String bindingNo</code> |
| <code>Long bindingVersion</code> |
| <code>String orderNo</code> |
| <code>String storeOrderNo</code> |
| <code>String frozenRequestHash</code> |
| <code>List<MembershipScope> memberships</code> |
| <code>@JsonInclude(Include.NON_NULL) String amendmentSourceHash</code> |

### AllocationSource

| 字段声明（含校验） |
| --- |
| <code>String allocationNo</code> |
| <code>String batchNo</code> |
| <code>String foodLineNo</code> |
| <code>String sourceLineRef</code> |
| <code>String sourceComponentRef</code> |
| <code>Long orderItemId</code> |
| <code>BigDecimal originalQty</code> |
| <code>String unit</code> |
| <code>String coreSnapshotHash</code> |
| <code>Long allocationVersion</code> |
| <code>Long lineVersion</code> |
| <code>Long selectionVersion</code> |
| <code>String lineSnapshotHash</code> |
| <code>String currency</code> |
| <code>long originalAmountMinor</code> |
| <code>@JsonInclude(Include.NON_NULL) BigDecimal effectiveQty</code> |
| <code>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor</code> |
| <code>@JsonInclude(Include.NON_NULL) String amendmentSourceHash</code> |

### SourceSnapshot

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String checkNo</code> |
| <code>Long checkVersion</code> |
| <code>String currency</code> |
| <code>long originalAmountMinor</code> |
| <code>List<BatchSource> batches</code> |
| <code>List<AllocationSource> allocations</code> |
| <code>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor</code> |

### ShareCell

| 字段声明（含校验） |
| --- |
| <code>String allocationNo</code> |
| <code>String foodLineNo</code> |
| <code>Long orderItemId</code> |
| <code>String personNo</code> |
| <code>long amountMinor</code> |

### PersonTotal

| 字段声明（含校验） |
| --- |
| <code>String personNo</code> |
| <code>long amountMinor</code> |

### Division

| 字段声明（含校验） |
| --- |
| <code>List<PersonTotal> people</code> |
| <code>List<ShareCell> cells</code> |

### PlanVO

| 字段声明（含校验） |
| --- |
| <code>String planNo</code> |
| <code>String checkNo</code> |
| <code>long version</code> |
| <code>String status</code> |
| <code>String storedStatus</code> |
| <code>String mode</code> |
| <code>String currency</code> |
| <code>long originalAmountMinor</code> |
| <code>String sourceHash</code> |
| <code>String planHash</code> |
| <code>boolean localSourcesCurrent</code> |
| <code>boolean settlementEligible</code> |
| <code>boolean financialAuthorityPending</code> |
| <code>SourceSnapshot source</code> |
| <code>Division division</code> |
| <code>CreateReq definition</code> |
| <code>String reason</code> |
| <code>String createdBy</code> |
| <code>String confirmedBy</code> |
| <code>String cancelledBy</code> |
| <code>@JsonInclude(Include.NON_NULL) Long effectiveAmountMinor</code> |

## ai.neargo.shop.food.dto.CancelDiningOrderReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CancelDiningOrderReq.java)

### CancelDiningOrderReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotBlank @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason</code> |

## ai.neargo.shop.food.dto.CartAccess

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartAccess.java)

### CartAccess

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo</code> |

## ai.neargo.shop.food.dto.CartBundleChoiceInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartBundleChoiceInput.java)

### CartBundleChoiceInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String choiceNo</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty</code> |

## ai.neargo.shop.food.dto.CartResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartResultVO.java)

### CartResultVO

| 字段声明（含校验） |
| --- |
| <code>CartVO cart</code> |

## ai.neargo.shop.food.dto.CartSelectionInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartSelectionInput.java)

### CartSelectionInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String groupNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceKind</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion</code> |

## ai.neargo.shop.food.dto.CartSessionGrantVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartSessionGrantVO.java)

### CartSessionGrantVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.CartVersionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartVersionReq.java)

### CartVersionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.CartVersionVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartVersionVO.java)

### CartVersionVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.CartVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CartVO.java)

### CartVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>Instant scheduledAt</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>List<OrderLineInput> lines</code> |

## ai.neargo.shop.food.dto.CatalogDependencyVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogDependencyVO.java)

### CatalogDependencyVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash</code> |
| <code>OptionGroupConfigVO optionGroup</code> |
| <code>CatalogModifierVO modifier</code> |
| <code>BundleConfigVO bundle</code> |
| <code>CatalogRecipeVO recipe</code> |
| <code>List<CatalogSpecValueVO> specValues</code> |

## ai.neargo.shop.food.dto.CatalogItemVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogItemVO.java)

### CatalogItemVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String categoryNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String categoryName</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String title</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String cover</code> |
| <code>DishVO dish</code> |
| <code>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> optionGroupNos</code> |
| <code>List<CatalogSkuVO> skus</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) List<MenuSkuPriceInput> skuPrices</code> |

## ai.neargo.shop.food.dto.CatalogMenuVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogMenuVO.java)

### CatalogMenuVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup</code> |
| <code>List<CatalogDependencyVO> dependencies</code> |

## ai.neargo.shop.food.dto.CatalogModifierVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogModifierVO.java)

### CatalogModifierVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String kind</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String salesSkuNo</code> |
| <code>CatalogSkuVO salesOffer</code> |

## ai.neargo.shop.food.dto.CatalogQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogQuery.java)

### CatalogQuery

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo</code> |

## ai.neargo.shop.food.dto.CatalogRecipeVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogRecipeVO.java)

### CatalogRecipeVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion</code> |

## ai.neargo.shop.food.dto.CatalogSkuVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogSkuVO.java)

### CatalogSkuVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String spec</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |
| <code>@JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) Long priceMinor</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String currency</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricingStatus</code> |
| <code>@JsonDeserialize(using=DishOfferFacts.AvailableDeserializer.class) Integer available</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stockStatus</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean onSale</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean restricted</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean transactable</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean requiresSelectionQuote</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason</code> |

## ai.neargo.shop.food.dto.CatalogSpecValueVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogSpecValueVO.java)

### CatalogSpecValueVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String label</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimName</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueType</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String usageType</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long valueVersion</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long dimVersion</code> |

## ai.neargo.shop.food.dto.CatalogVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CatalogVO.java)

### CatalogVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf</code> |
| <code>List<CatalogMenuVO> menus</code> |
| <code>List<CatalogItemVO> items</code> |

## ai.neargo.shop.food.dto.CheckCollaborationContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CheckCollaborationContracts.java)

### CheckVersion

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String checkNo</code> |
| <code>@Min(0) long version</code> |

### MoveReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Valid CheckVersion source</code> |
| <code>@NotNull @Valid CheckVersion target</code> |
| <code>@NotEmpty @Size(max = 100) List<@NotBlank String> batchNos</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### MoveVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String sourceCheckNo</code> |
| <code>String targetCheckNo</code> |
| <code>List<String> batchNos</code> |
| <code>long sourceVersion</code> |
| <code>long targetVersion</code> |
| <code>String status</code> |

### LineMoveReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Valid CheckVersion source</code> |
| <code>@NotNull @Valid CheckVersion target</code> |
| <code>@NotEmpty @Size(max = 100) List<@NotBlank String> foodLineNos</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### LineMoveVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String sourceCheckNo</code> |
| <code>String targetCheckNo</code> |
| <code>List<String> foodLineNos</code> |
| <code>String status</code> |

### ReceivableBatch

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |
| <code>String currency</code> |
| <code>Long originalMinor</code> |
| <code>Long effectiveMinor</code> |
| <code>Long adjustmentMinor</code> |
| <code>String amendmentSourceHash</code> |

### ReceivablePreview

| 字段声明（含校验） |
| --- |
| <code>String checkNo</code> |
| <code>List<ReceivableBatch> batches</code> |
| <code>String sourceHash</code> |
| <code>boolean financialActionAllowed</code> |

### FreezeReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long checkVersion</code> |
| <code>@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceHash</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### FreezeVO

| 字段声明（含校验） |
| --- |
| <code>String freezeNo</code> |
| <code>String checkNo</code> |
| <code>String sourceHash</code> |
| <code>String status</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.CheckDetailVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CheckDetailVO.java)

### CheckDetailVO

| 字段声明（含校验） |
| --- |
| <code>CheckVO check</code> |
| <code>List<SeatReq> seats</code> |
| <code>List<OccupancyVO> occupancies</code> |

## ai.neargo.shop.food.dto.CheckResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CheckResultVO.java)

### CheckResultVO

| 字段声明（含校验） |
| --- |
| <code>CheckVO check</code> |

## ai.neargo.shop.food.dto.CheckVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CheckVO.java)

### CheckVO

| 字段声明（含校验） |
| --- |
| <code>String checkNo</code> |
| <code>String storeNo</code> |
| <code>LocalDate businessDate</code> |
| <code>String status</code> |
| <code>String payMode</code> |
| <code>int partySize</code> |
| <code>List<String> tableNos</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.ClearCheckReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ClearCheckReq.java)

### ClearCheckReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |
| <code>@Size(max=64) String approvalRef</code> |
| <code>@NotNull @Size(max=100) List<@NotBlank @Size(max=32) String> occupancyNos</code> |

## ai.neargo.shop.food.dto.CombineTablesReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CombineTablesReq.java)

### CombineTablesReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotEmpty @Valid @Size(max=100) List<TableVersionReq> tableVersions</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.ConfigRevisionContentVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ConfigRevisionContentVO.java)

### ConfigRevisionContentVO

| 字段声明（含校验） |
| --- |
| <code>ConfigRevisionVO publication</code> |
| <code>String definitionSnapshot</code> |

## ai.neargo.shop.food.dto.ConfigRevisionVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ConfigRevisionVO.java)

### ConfigRevisionVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision</code> |
| <code>OffsetDateTime effectiveAt</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |

## ai.neargo.shop.food.dto.CreateBundleReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateBundleReq.java)

### CreateBundleReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String skuNo</code> |

## ai.neargo.shop.food.dto.CreateBundleResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateBundleResultVO.java)

### CreateBundleResultVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>String status</code> |

## ai.neargo.shop.food.dto.CreateCartReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateCartReq.java)

### CreateCartReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>Instant scheduledAt</code> |

## ai.neargo.shop.food.dto.CreateCustomerCartReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateCustomerCartReq.java)

### CreateCustomerCartReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>Instant scheduledAt</code> |

## ai.neargo.shop.food.dto.CreateDiningOrderReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateDiningOrderReq.java)

### CreateDiningOrderReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@NotBlank @Size(max = 32) String quoteNo</code> |
| <code>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest</code> |
| <code>@NotBlank @Pattern(regexp = "PREPAY&#124;POSTPAY") String payMode</code> |

## ai.neargo.shop.food.dto.CreateDiningSettlementReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateDiningSettlementReq.java)

### CreateDiningSettlementReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest</code> |

## ai.neargo.shop.food.dto.CreateMenuChannelBindingReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuChannelBindingReq.java)

### CreateMenuChannelBindingReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelAccountRef</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef</code> |

## ai.neargo.shop.food.dto.CreateMenuReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuReq.java)

### CreateMenuReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType</code> |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone</code> |
| <code>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority</code> |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup</code> |

## ai.neargo.shop.food.dto.CreateMenuResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateMenuResultVO.java)

### CreateMenuResultVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |

## ai.neargo.shop.food.dto.CreateModifierReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateModifierReq.java)

### CreateModifierReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotBlank @Size(max=16) String kind</code> |
| <code>@Size(max=32) String salesSkuNo</code> |
| <code>@NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=16) String inventoryMode</code> |

## ai.neargo.shop.food.dto.CreateModifierResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateModifierResultVO.java)

### CreateModifierResultVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>long version</code> |
| <code>String status</code> |

## ai.neargo.shop.food.dto.CreateOptionGroupReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateOptionGroupReq.java)

### CreateOptionGroupReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty</code> |
| <code>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty</code> |

## ai.neargo.shop.food.dto.CreateOptionGroupResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateOptionGroupResultVO.java)

### CreateOptionGroupResultVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>long version</code> |
| <code>String status</code> |

## ai.neargo.shop.food.dto.CreatePolicyReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreatePolicyReq.java)

### CreatePolicyReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 64) String timezone</code> |
| <code>@NotNull @Min(0) @Max(1439) Integer cutoverMinute</code> |
| <code>@NotNull Instant effectiveFrom</code> |
| <code>@NotBlank @Pattern(regexp = "PREPAY&#124;POSTPAY") String payPolicy</code> |
| <code>@NotNull @Size(max = 64) List<@NotBlank @Size(max = 64) String> capabilities</code> |
| <code>@Size(max = 64) String approvalRef</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.CreateRecipeReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateRecipeReq.java)

### CreateRecipeReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode</code> |
| <code>@NotNull BigDecimal yieldQty</code> |
| <code>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit</code> |

## ai.neargo.shop.food.dto.CreateStaffDiningOrderReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateStaffDiningOrderReq.java)

### CreateStaffDiningOrderReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @Size(max = 32) String buyerNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String quoteNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "POSTPAY") String payMode</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 32) String partyNo</code> |

## ai.neargo.shop.food.dto.CreateTableAreaReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateTableAreaReq.java)

### CreateTableAreaReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String code</code> |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull Integer sortNo</code> |

## ai.neargo.shop.food.dto.CreateTableReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/CreateTableReq.java)

### CreateTableReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String areaNo</code> |
| <code>@NotBlank @Size(max=32) String code</code> |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull @Min(1) Integer capacity</code> |
| <code>@NotNull Integer sortNo</code> |
| <code>@Size(max=32) String resourceNo</code> |

## ai.neargo.shop.food.dto.DiningBatchSummaryVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningBatchSummaryVO.java)

### DiningBatchSummaryVO

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String storeNo</code> |
| <code>String originalCheckNo</code> |
| <code>List<String> currentCheckNos</code> |
| <code>boolean membershipAuthoritative</code> |
| <code>String status</code> |
| <code>String orderSource</code> |
| <code>LocalDate businessDate</code> |
| <code>String fulfillment</code> |
| <code>String payMode</code> |
| <code>String quoteNo</code> |
| <code>String quoteHash</code> |
| <code>Instant createdAt</code> |
| <code>Instant acceptedAt</code> |
| <code>Instant scheduledAt</code> |
| <code>long version</code> |
| <code>List<OriginalBindingVO> originalBindings</code> |

### OriginalBindingVO

| 字段声明（含校验） |
| --- |
| <code>String bindingNo</code> |
| <code>String paymentRootNo</code> |
| <code>String storeOrderNo</code> |
| <code>String bindingStatus</code> |

## ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningChannelFulfillmentFacts.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String batchNo</code> |
| <code>String coreOrderNo</code> |
| <code>String custodyOperationNo</code> |
| <code>String sourceRevision</code> |

### Line

| 字段声明（含校验） |
| --- |
| <code>String sourceLineRef</code> |
| <code>String componentRef</code> |
| <code>String coreItemRef</code> |
| <code>BigDecimal quantity</code> |
| <code>String unit</code> |

### Facts

| 字段声明（含校验） |
| --- |
| <code>Query query</code> |
| <code>List<Line> lines</code> |
| <code>Instant occurredAt</code> |
| <code>String sourceReceiptRef</code> |
| <code>Instant observedAt</code> |
| <code>Instant validUntil</code> |

## ai.neargo.shop.food.dto.DiningChannelSourceFacts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningChannelSourceFacts.java)

### DiningChannelSourceFacts

| 字段声明（含校验） |
| --- |
| <code>Key key</code> |
| <code>Original original</code> |
| <code>Current current</code> |
| <code>Instant observedAt</code> |
| <code>Instant validUntil</code> |
| <code>String sourceReceiptRef</code> |

### Key

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String importNo</code> |
| <code>String originalDocumentHash</code> |

### PartyDescription

| 字段声明（含校验） |
| --- |
| <code>String externalCustomerRef</code> |
| <code>String displayName</code> |

### Current

| 字段声明（含校验） |
| --- |
| <code>String externalOrderRef</code> |
| <code>String documentHash</code> |
| <code>String financialHash</code> |
| <code>State state</code> |
| <code>Collection collection</code> |
| <code>Long collectedMinor</code> |
| <code>String originalCollectionRef</code> |
| <code>Instant originalAcceptedAt</code> |
| <code>String originalReadReceiptRef</code> |
| <code>String queryOperationNo</code> |
| <code>String queryAttemptRef</code> |

### Original

| 字段声明（含校验） |
| --- |
| <code>Channel channel</code> |
| <code>String connectionNo</code> |
| <code>String connectionRevisionNo</code> |
| <code>String externalSnapshotNo</code> |
| <code>String externalOrderRef</code> |
| <code>String documentHash</code> |
| <code>String financialHash</code> |
| <code>String originalReadReceiptRef</code> |
| <code>String currency</code> |
| <code>Collection originalCollection</code> |
| <code>String originalCollectionRef</code> |
| <code>Fulfillment fulfillment</code> |
| <code>PartyDescription party</code> |
| <code>Delivery delivery</code> |
| <code>Instant orderedAt</code> |
| <code>Instant scheduledAt</code> |
| <code>List<InvoiceLine> invoiceLines</code> |
| <code>Totals totals</code> |
| <code>List<Line> lines</code> |
| <code>List<Definition> definitions</code> |
| <code>String catalogHash</code> |
| <code>String catalogPublicationReceiptRef</code> |

### DefinitionRef

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>String configNo</code> |
| <code>String revisionNo</code> |
| <code>long publicationRevision</code> |
| <code>String contentHash</code> |

### Definition

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>String configNo</code> |
| <code>String revisionNo</code> |
| <code>long publicationRevision</code> |
| <code>String contentHash</code> |
| <code>String snapshot</code> |

### Line

| 字段声明（含校验） |
| --- |
| <code>String externalLineRef</code> |
| <code>String menuNo</code> |
| <code>String menuItemNo</code> |
| <code>String menuRevisionNo</code> |
| <code>String goodsNo</code> |
| <code>String skuNo</code> |
| <code>BigDecimal quantity</code> |
| <code>String unit</code> |
| <code>String note</code> |
| <code>List<CartSelectionInput> selections</code> |
| <code>List<CartBundleChoiceInput> bundleChoices</code> |
| <code>List<Component> components</code> |

### Component

| 字段声明（含校验） |
| --- |
| <code>String logicalKey</code> |
| <code>String externalLineRef</code> |
| <code>String parentExternalLineRef</code> |
| <code>String externalKind</code> |
| <code>String productionKind</code> |
| <code>String goodsNo</code> |
| <code>String skuNo</code> |
| <code>String title</code> |
| <code>String cover</code> |
| <code>String spec</code> |
| <code>BigDecimal physicalQuantity</code> |
| <code>String unit</code> |
| <code>long grossExTaxMinor</code> |
| <code>long merchantDiscountExTaxMinor</code> |
| <code>long taxMinor</code> |
| <code>long netMinor</code> |
| <code>String originalAllocationBasisRef</code> |
| <code>String recipeRevisionNo</code> |
| <code>String selectionGroupNo</code> |
| <code>String selectionSourceKind</code> |
| <code>String selectionSourceNo</code> |
| <code>String bundleChoiceNo</code> |

## ai.neargo.shop.food.dto.DiningDeliveryContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningDeliveryContracts.java)

### SaveReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long cartVersion</code> |
| <code>@NotBlank @Size(max = 64) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverName</code> |
| <code>@NotBlank @Size(max = 32) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiverPhone</code> |
| <code>@NotBlank @Size(max = 512) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String address</code> |
| <code>@Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String deliveryInstruction</code> |
| <code>@JsonInclude(Include.NON_NULL) Integer latE6</code> |
| <code>@JsonInclude(Include.NON_NULL) Integer lngE6</code> |

### DeliverySnapshot

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String cartNo</code> |
| <code>long cartVersion</code> |
| <code>long revision</code> |
| <code>String receiverName</code> |
| <code>String receiverPhone</code> |
| <code>String address</code> |
| <code>String deliveryInstruction</code> |
| <code>@JsonInclude(Include.NON_NULL) Integer latE6</code> |
| <code>@JsonInclude(Include.NON_NULL) Integer lngE6</code> |

## ai.neargo.shop.food.dto.DiningIdentityContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningIdentityContracts.java)

### RegisterDeviceReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String deviceNo</code> |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank @Size(max = 64) String displayName</code> |

### VersionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### DeviceVO

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>String displayName</code> |
| <code>String status</code> |
| <code>long generation</code> |
| <code>long version</code> |

### DeviceCredentialVO

| 字段声明（含校验） |
| --- |
| <code>DeviceVO device</code> |
| <code>String rawCredential</code> |

### StaffPartyReq

| 字段声明（含校验） |
| --- |
| <code>@Size(max = 32) String checkNo</code> |
| <code>@NotBlank @Size(max = 64) String displayName</code> |
| <code>@Min(60) @Max(86400) int lifetimeSeconds</code> |

### DeviceSessionReq

| 字段声明（含校验） |
| --- |
| <code>@Min(60) @Max(86400) int lifetimeSeconds</code> |

### PartyVO

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String sessionNo</code> |
| <code>String deviceNo</code> |
| <code>String displayName</code> |
| <code>Instant expiresAt</code> |
| <code>List<String> scope</code> |
| <code>String checkNo</code> |
| <code>String buyerNo</code> |
| <code>long deviceGeneration</code> |
| <code>long version</code> |

### SessionCredentialVO

| 字段声明（含校验） |
| --- |
| <code>PartyVO party</code> |
| <code>String accessToken</code> |

## ai.neargo.shop.food.dto.DiningOrderBatchVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningOrderBatchVO.java)

### DiningOrderBatchVO

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String storeNo</code> |
| <code>String checkNo</code> |
| <code>String cartNo</code> |
| <code>long cartVersion</code> |
| <code>String quoteNo</code> |
| <code>String status</code> |
| <code>String payMode</code> |
| <code>LocalDate businessDate</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |
| <code>String coreStatus</code> |
| <code>String currency</code> |
| <code>Long amountMinor</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.DiningPickupContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java)

### VerifyPickupReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long expectedVersion</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9]{6}") String verifyCode</code> |
| <code>String custodyOperationNo</code> |

### PickupVerificationVO

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>boolean localHandoverRecorded</code> |
| <code>Receipt originalFulfillment</code> |

## ai.neargo.shop.food.dto.DiningPriceFacts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPriceFacts.java)

### DiningPriceFacts

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion</code> |
| <code>Payload payload</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceHash</code> |

### Payload

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String subjectNo</code> |
| <code>CartVO cart</code> |
| <code>List<MenuRef> menus</code> |
| <code>List<SkuIdentityFact> identities</code> |
| <code>List<Offer> offers</code> |
| <code>List<Restriction> restrictions</code> |
| <code>List<Unit> units</code> |
| <code>Composition composition</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor</code> |
| <code>@JsonInclude(Include.NON_NULL) DiningDeliveryContracts.DeliverySnapshot delivery</code> |
| <code>@JsonInclude(Include.NON_NULL) List<Source> feeSources</code> |

### Composition

| 字段声明（含校验） |
| --- |
| <code>List<CatalogMenuVO> menus</code> |
| <code>List<CatalogItemVO> items</code> |

### Unit

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String componentRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String priceRevision</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor</code> |

### MenuRef

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash</code> |
| <code>List<DependencyRef> dependencies</code> |

### DependencyRef

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash</code> |

### Restriction

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean blocked</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota</code> |
| <code>@JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean originalStockKnown</code> |
| <code>List<SalesRestrictionVO> rules</code> |

## ai.neargo.shop.food.dto.DiningQuoteConfirmReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningQuoteConfirmReq.java)

### DiningQuoteConfirmReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest</code> |

## ai.neargo.shop.food.dto.DiningQuoteReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningQuoteReq.java)

### DiningQuoteReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@Valid Selection selection</code> |

## ai.neargo.shop.food.dto.DiningReportExportReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningReportExportReq.java)

### DiningReportExportReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull View view</code> |
| <code>LocalDate from</code> |
| <code>LocalDate to</code> |
| <code>LocalDate businessDate</code> |
| <code>@Size(max=30) List<String> storeNos</code> |
| <code>Integer metricVersion</code> |
| <code>@Size(max=64) Set<String> metricCodes</code> |
| <code>Integer limit</code> |
| <code>MeasureFilter measure</code> |

## ai.neargo.shop.food.dto.DiningReturnApplyReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningReturnApplyReq.java)

### DiningReturnApplyReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String batchNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String type</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reasonCode</code> |
| <code>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> images</code> |
| <code>List<Line> lines</code> |

### Line

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef</code> |
| <code>@JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity</code> |

## ai.neargo.shop.food.dto.DiningReturnVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningReturnVO.java)

### DiningReturnVO

| 字段声明（含校验） |
| --- |
| <code>String returnNo</code> |
| <code>String batchNo</code> |
| <code>String storeNo</code> |
| <code>String status</code> |
| <code>String financialStatus</code> |
| <code>String kitchenStatus</code> |
| <code>String coreCaseRef</code> |
| <code>String reason</code> |
| <code>List<Line> lines</code> |
| <code>IndustryAfterSalePort.Result financialCase</code> |

### Line

| 字段声明（含校验） |
| --- |
| <code>String sourceLineRef</code> |
| <code>BigDecimal quantity</code> |
| <code>String kitchenDisposition</code> |

## ai.neargo.shop.food.dto.DiningSessionTableVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSessionTableVO.java)

### DiningSessionTableVO

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String tableNo</code> |
| <code>Instant expiresAt</code> |

## ai.neargo.shop.food.dto.DiningSessionVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSessionVO.java)

### DiningSessionVO

| 字段声明（含校验） |
| --- |
| <code>String sessionNo</code> |
| <code>String accessToken</code> |
| <code>Instant expiresAt</code> |
| <code>String checkNo</code> |

## ai.neargo.shop.food.dto.DiningSettlementVersionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSettlementVersionReq.java)

### DiningSettlementVersionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |

## ai.neargo.shop.food.dto.DiningSettlementVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningSettlementVO.java)

### DiningSettlementVO

| 字段声明（含校验） |
| --- |
| <code>String settlementNo</code> |
| <code>String storeNo</code> |
| <code>String batchNo</code> |
| <code>String checkNo</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |
| <code>String status</code> |
| <code>String currency</code> |
| <code>long dueMinor</code> |
| <code>String scopeHash</code> |
| <code>String confirmationDigest</code> |
| <code>long version</code> |
| <code>String batchStatus</code> |
| <code>String coreOrderStatus</code> |
| <code>PaymentVO payment</code> |
| <code>boolean readyToAccept</code> |

## ai.neargo.shop.food.dto.DisableMenuReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DisableMenuReq.java)

### DisableMenuReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotBlank @Size(max=255) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason</code> |

## ai.neargo.shop.food.dto.DishCatalogItemVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishCatalogItemVO.java)

### DishCatalogItemVO

| 字段声明（含校验） |
| --- |
| <code>DishVO dish</code> |
| <code>GoodsSummary product</code> |

## ai.neargo.shop.food.dto.DishDetailResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishDetailResultVO.java)

### DishDetailResultVO

| 字段声明（含校验） |
| --- |
| <code>DishVO dish</code> |
| <code>List<OptionGroupConfigVO> optionGroups</code> |
| <code>String coreCatalogRef</code> |
| <code>GoodsFact coreCatalog</code> |

## ai.neargo.shop.food.dto.DishPresentation

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishPresentation.java)

### DishPresentation

| 字段声明（含校验） |
| --- |
| <code>String displayName</code> |
| <code>List<LocalizedName> localizedNames</code> |
| <code>List<String> imageRefs</code> |
| <code>List<String> tags</code> |
| <code>List<String> allergenNotes</code> |

### LocalizedName

| 字段声明（含校验） |
| --- |
| <code>String locale</code> |
| <code>String name</code> |

## ai.neargo.shop.food.dto.DishResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishResultVO.java)

### DishResultVO

| 字段声明（含校验） |
| --- |
| <code>DishVO dish</code> |

## ai.neargo.shop.food.dto.DishVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DishVO.java)

### DishVO

| 字段声明（含校验） |
| --- |
| <code>String goodsNo</code> |
| <code>String dishKind</code> |
| <code>int prepSeconds</code> |
| <code>DishPresentation presentation</code> |
| <code>MeasurementPolicy measurement</code> |
| <code>long configVersion</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.ExceptionInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ExceptionInput.java)

### ExceptionInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String localDate</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String startTime</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String endTime</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer endDayOffset</code> |

## ai.neargo.shop.food.dto.FoodFeeSourceContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodFeeSourceContracts.java)

### BindReq

| 字段声明（含校验） |
| --- |
| <code>long expectedVersion</code> |
| <code>String checkNo</code> |
| <code>@NotBlank String ruleNo</code> |
| <code>@NotBlank String revisionNo</code> |
| <code>@NotNull Kind kind</code> |
| <code>@NotBlank String unit</code> |
| <code>BigDecimal packageQuantity</code> |
| <code>List<String> basisSourceRefs</code> |
| <code>String waiverEvidence</code> |
| <code>@NotBlank String reason</code> |
| <code>boolean enabled</code> |
| <code>String contractScopeNo</code> |

### BindingVO

| 字段声明（含校验） |
| --- |
| <code>String bindingNo</code> |
| <code>String checkNo</code> |
| <code>String ruleNo</code> |
| <code>String revisionNo</code> |
| <code>Kind kind</code> |
| <code>String unit</code> |
| <code>BigDecimal packageQuantity</code> |
| <code>List<String> basisSourceRefs</code> |
| <code>String waiverEvidence</code> |
| <code>boolean enabled</code> |
| <code>long version</code> |
| <code>String contractScopeNo</code> |

## ai.neargo.shop.food.dto.FoodKdsContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodKdsContracts.java)

### GrantReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String deviceNo</code> |
| <code>@NotBlank @Size(max = 32) String deviceActorNo</code> |
| <code>@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> stationNos</code> |
| <code>@Min(60) @Max(86400) int lifetimeSeconds</code> |

### RevokeReq

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### SubscriptionVO

| 字段声明（含校验） |
| --- |
| <code>String subscriptionNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>String actorNo</code> |
| <code>List<String> stationNos</code> |
| <code>String expiresAt</code> |
| <code>String status</code> |
| <code>long version</code> |

### EventDraft

| 字段声明（含校验） |
| --- |
| <code>String eventNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String stationNo</code> |
| <code>String eventType</code> |
| <code>String sourceNo</code> |
| <code>long sourceVersion</code> |
| <code>String payload</code> |
| <code>String occurredAt</code> |

### EventVO

| 字段声明（含校验） |
| --- |
| <code>long cursor</code> |
| <code>String eventNo</code> |
| <code>String stationNo</code> |
| <code>String eventType</code> |
| <code>String sourceNo</code> |
| <code>long sourceVersion</code> |
| <code>String payload</code> |
| <code>String occurredAt</code> |

### ReplayVO

| 字段声明（含校验） |
| --- |
| <code>String subscriptionNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>List<EventVO> events</code> |
| <code>long nextCursor</code> |
| <code>long watermark</code> |
| <code>boolean hasMore</code> |

## ai.neargo.shop.food.dto.FoodStationRuntimeContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodStationRuntimeContracts.java)

### ControlReq

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(value = "version", required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@NotBlank String action</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |
| <code>@Size(max = 32) String deviceNo</code> |
| <code>@Size(max = 128) String voiceText</code> |

### StateVO

| 字段声明（含校验） |
| --- |
| <code>String stationNo</code> |
| <code>String status</code> |
| <code>long version</code> |

### CommandVO

| 字段声明（含校验） |
| --- |
| <code>String commandNo</code> |
| <code>String stationNo</code> |
| <code>String action</code> |
| <code>String status</code> |
| <code>long sourceVersion</code> |
| <code>String reason</code> |

### VoiceClaim

| 字段声明（含校验） |
| --- |
| <code>String commandNo</code> |
| <code>String stationNo</code> |
| <code>String deviceNo</code> |
| <code>String voiceText</code> |
| <code>String leaseToken</code> |
| <code>String leaseUntil</code> |

### VoiceReceipt

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String commandNo</code> |
| <code>@NotBlank @Size(max = 64) String leaseToken</code> |
| <code>@NotBlank String status</code> |
| <code>@Size(max = 64) String responseCode</code> |

### VoicePoll

| 字段声明（含校验） |
| --- |
| <code>VoiceClaim claim</code> |

## ai.neargo.shop.food.dto.FoodStockContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodStockContracts.java)

### CreateItem

| 字段声明（含校验） |
| --- |
| <code>String itemKind</code> |
| <code>String skuNo</code> |
| <code>String name</code> |
| <code>String baseUnit</code> |
| <code>String trackingMode</code> |
| <code>@tools.jackson.databind.annotation.JsonDeserialize(using=ai.neargo.shop.spi.inventory.MaterialCatalogFacts.WeighedDeserializer.class) Integer weighed</code> |

### UpdateItem

| 字段声明（含校验） |
| --- |
| <code>String name</code> |
| <code>String status</code> |
| <code>String trackingMode</code> |
| <code>Long version</code> |
| <code>@tools.jackson.databind.annotation.JsonDeserialize(using=ai.neargo.shop.spi.inventory.MaterialCatalogFacts.WeighedDeserializer.class) Integer weighed</code> |

### Initialize

| 字段声明（含校验） |
| --- |
| <code>String itemNo</code> |
| <code>String trackingMode</code> |
| <code>String status</code> |
| <code>BigDecimal initialQty</code> |
| <code>String reasonCode</code> |
| <code>String reasonDetail</code> |

### Policy

| 字段声明（含校验） |
| --- |
| <code>String trackingMode</code> |
| <code>String status</code> |
| <code>Long version</code> |
| <code>String reasonCode</code> |
| <code>String reasonDetail</code> |

### Adjust

| 字段声明（含校验） |
| --- |
| <code>BigDecimal delta</code> |
| <code>Long version</code> |
| <code>String reasonCode</code> |
| <code>String reasonDetail</code> |

### ItemVO

| 字段声明（含校验） |
| --- |
| <code>String itemNo</code> |
| <code>String itemKind</code> |
| <code>String skuNo</code> |
| <code>String name</code> |
| <code>String baseUnit</code> |
| <code>String trackingMode</code> |
| <code>String status</code> |
| <code>long version</code> |
| <code>Integer weighed</code> |

### BalanceVO

| 字段声明（含校验） |
| --- |
| <code>String balanceNo</code> |
| <code>String itemNo</code> |
| <code>String baseUnit</code> |
| <code>String trackingMode</code> |
| <code>String status</code> |
| <code>BigDecimal onHand</code> |
| <code>BigDecimal reservedQty</code> |
| <code>BigDecimal availableQty</code> |
| <code>long version</code> |
| <code>String commandNo</code> |

### MovementVO

| 字段声明（含校验） |
| --- |
| <code>String movementNo</code> |
| <code>String commandNo</code> |
| <code>String itemNo</code> |
| <code>String reservationNo</code> |
| <code>String action</code> |
| <code>BigDecimal quantity</code> |
| <code>String baseUnit</code> |
| <code>BigDecimal onHandBefore</code> |
| <code>BigDecimal onHandAfter</code> |
| <code>BigDecimal reservedBefore</code> |
| <code>BigDecimal reservedAfter</code> |
| <code>String reasonCode</code> |
| <code>String reasonDetail</code> |
| <code>String actorNo</code> |
| <code>LocalDateTime occurredAt</code> |

## ai.neargo.shop.food.dto.FoodSupportContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/FoodSupportContracts.java)

### ReadinessVO

| 字段声明（含校验） |
| --- |
| <code>String deployment</code> |
| <code>boolean ready</code> |
| <code>boolean schemaValidated</code> |
| <code>String schemaVersion</code> |
| <code>List<String> missingCapabilities</code> |
| <code>Map<String, Integer> typedProviderCounts</code> |

## ai.neargo.shop.food.dto.GetBundleVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/GetBundleVO.java)

### GetBundleVO

| 字段声明（含校验） |
| --- |
| <code>BundleConfigVO config</code> |

## ai.neargo.shop.food.dto.GetPolicyQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/GetPolicyQuery.java)

### GetPolicyQuery

| 字段声明（含校验） |
| --- |
| <code>@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at</code> |

## ai.neargo.shop.food.dto.KitchenContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java)

### FrozenRoute

| 字段声明（含校验） |
| --- |
| <code>String skuNo</code> |
| <code>String stationNo</code> |
| <code>String taskRole</code> |
| <code>long routeVersion</code> |
| <code>String revisionNo</code> |
| <code>String contentHash</code> |

### FrozenRoutes

| 字段声明（含校验） |
| --- |
| <code>List<FrozenRoute> routes</code> |

### TaskActionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max=24) String action</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty</code> |
| <code>@Size(max=255) String reason</code> |

### TaskDeltaReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty</code> |
| <code>@Size(max=255) String reason</code> |

### RemakeReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

### HandoverLine

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String foodLineNo</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty</code> |

### HandoverReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String kind</code> |
| <code>@NotNull List<HandoverLine> lines</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |
| <code>@Size(max=64) String recipientNo</code> |
| <code>@Size(max=128) String recipientRef</code> |

### PackingReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull List<HandoverLine> lines</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

### CustodyReceiptVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String batchNo</code> |
| <code>String orderNo</code> |
| <code>String storeOrderNo</code> |
| <code>String bindingNo</code> |
| <code>Long bindingVersion</code> |
| <code>Long batchVersion</code> |
| <code>String frozenRequestHash</code> |
| <code>String action</code> |
| <code>String kind</code> |
| <code>List<HandoverLine> lines</code> |
| <code>List<CustodySourceLine> sources</code> |
| <code>String recipientNo</code> |
| <code>String recipientRef</code> |
| <code>String actorNo</code> |
| <code>String recordedAt</code> |
| <code>boolean complete</code> |
| <code>boolean coreDeliveryPending</code> |

### CustodySourceLine

| 字段声明（含校验） |
| --- |
| <code>String foodLineNo</code> |
| <code>String sourceLineRef</code> |
| <code>List<CoreLineScope> originalLines</code> |
| <code>List<KitchenTaskScope> tasks</code> |

### CoreLineScope

| 字段声明（含校验） |
| --- |
| <code>String allocationNo</code> |
| <code>Long orderItemId</code> |
| <code>String sourceComponentRef</code> |
| <code>BigDecimal originalQty</code> |
| <code>String unit</code> |
| <code>String coreSnapshotHash</code> |
| <code>Long version</code> |

### KitchenTaskScope

| 字段声明（含校验） |
| --- |
| <code>String taskNo</code> |
| <code>String componentNo</code> |
| <code>String stationNo</code> |
| <code>String taskRole</code> |
| <code>long routeVersion</code> |
| <code>long version</code> |

### HandoverVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String batchNo</code> |
| <code>String status</code> |
| <code>boolean deliveryPending</code> |
| <code>String coreDeliveryRef</code> |

### CourseReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull Integer sequenceNo</code> |

### CourseActionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotBlank String action</code> |
| <code>@Size(max=255) String reason</code> |
| <code>List<String> seatNos</code> |

### CourseFireReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotNull @Size(max=100) List<@NotBlank String> seatNos</code> |

### CourseVO

| 字段声明（含校验） |
| --- |
| <code>String courseNo</code> |
| <code>String checkNo</code> |
| <code>String name</code> |
| <code>int sequenceNo</code> |
| <code>String status</code> |
| <code>long version</code> |

### TaskVO

| 字段声明（含校验） |
| --- |
| <code>String taskNo</code> |
| <code>String ticketNo</code> |
| <code>String batchNo</code> |
| <code>String foodLineNo</code> |
| <code>String componentNo</code> |
| <code>String stationNo</code> |
| <code>String taskRole</code> |
| <code>long routeVersion</code> |
| <code>BigDecimal qty</code> |
| <code>BigDecimal makingQty</code> |
| <code>BigDecimal readyQty</code> |
| <code>BigDecimal servedQty</code> |
| <code>BigDecimal voidQty</code> |
| <code>String status</code> |
| <code>String seatNo</code> |
| <code>String courseNo</code> |
| <code>long version</code> |
| <code>String ticketKind</code> |
| <code>String originalTicketNo</code> |

### EventVO

| 字段声明（含校验） |
| --- |
| <code>String eventNo</code> |
| <code>String taskNo</code> |
| <code>String action</code> |
| <code>BigDecimal qty</code> |
| <code>String reason</code> |
| <code>String inventoryDocNo</code> |

### TicketVO

| 字段声明（含校验） |
| --- |
| <code>String ticketNo</code> |
| <code>String batchNo</code> |
| <code>String kind</code> |
| <code>String originalTicketNo</code> |
| <code>String status</code> |
| <code>List<TaskVO> tasks</code> |

## ai.neargo.shop.food.dto.KitchenReturnContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java)

### ItemDecision

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String returnItemNo</code> |
| <code>@NotBlank String disposition</code> |

### HandleReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version</code> |
| <code>@NotNull @Size(min=1,max=1000) List<@Valid ItemDecision> decisions</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

### ItemVO

| 字段声明（含校验） |
| --- |
| <code>String returnItemNo</code> |
| <code>String foodLineNo</code> |
| <code>String componentNo</code> |
| <code>BigDecimal qty</code> |
| <code>String disposition</code> |

### ReturnKitchenVO

| 字段声明（含校验） |
| --- |
| <code>String returnNo</code> |
| <code>String batchNo</code> |
| <code>String kitchenStatus</code> |
| <code>long version</code> |
| <code>List<ItemVO> items</code> |

### HandledVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String returnNo</code> |
| <code>String kitchenStatus</code> |

## ai.neargo.shop.food.dto.ListBundlesQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListBundlesQuery.java)

### ListBundlesQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |

## ai.neargo.shop.food.dto.ListCheckActionsQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListCheckActionsQuery.java)

### ListCheckActionsQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |

## ai.neargo.shop.food.dto.ListChecksQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListChecksQuery.java)

### ListChecksQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>String status</code> |
| <code>LocalDate businessDate</code> |

## ai.neargo.shop.food.dto.ListConfigRevisionsQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListConfigRevisionsQuery.java)

### ListConfigRevisionsQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@Min(1) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer page</code> |
| <code>@Min(1) @Max(100) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer size</code> |

## ai.neargo.shop.food.dto.ListDiningBatchesQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListDiningBatchesQuery.java)

### ListDiningBatchesQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) @Max(10000) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>@Size(max=32) String originalCheckNo</code> |
| <code>@Size(max=32) String checkNo</code> |
| <code>@Size(max=24) String status</code> |
| <code>@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate businessDate</code> |
| <code>@Size(max=24) String orderSource</code> |

## ai.neargo.shop.food.dto.ListDishesQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListDishesQuery.java)

### ListDishesQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>@Size(max=128) String keyword</code> |
| <code>@Pattern(regexp="SINGLE&#124;BUNDLE&#124;ADDON") String dishKind</code> |

## ai.neargo.shop.food.dto.ListMenusQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListMenusQuery.java)

### ListMenusQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page</code> |
| <code>@Min(1) @Max(100) @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size</code> |
| <code>@Pattern(regexp="DRAFT&#124;PUBLISHED&#124;DISABLED") @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String keyword</code> |

## ai.neargo.shop.food.dto.ListModifiersQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListModifiersQuery.java)

### ListModifiersQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |

## ai.neargo.shop.food.dto.ListOptionGroupsQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListOptionGroupsQuery.java)

### ListOptionGroupsQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |

## ai.neargo.shop.food.dto.ListRecipesQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListRecipesQuery.java)

### ListRecipesQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) @JsonDeserialize(using=PageDeserializer.class) Integer page</code> |
| <code>@Min(1) @Max(100) @JsonDeserialize(using=PageDeserializer.class) Integer size</code> |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@Pattern(regexp="DRAFT&#124;ACTIVE&#124;DISABLED") @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |

## ai.neargo.shop.food.dto.ListReservationsQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListReservationsQuery.java)

### ListReservationsQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>@NotNull Instant from</code> |
| <code>@NotNull Instant to</code> |
| <code>String status</code> |

## ai.neargo.shop.food.dto.ListTableAreasQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListTableAreasQuery.java)

### ListTableAreasQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>@Pattern(regexp="ACTIVE&#124;DISABLED") String status</code> |

## ai.neargo.shop.food.dto.ListTablesQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ListTablesQuery.java)

### ListTablesQuery

| 字段声明（含校验） |
| --- |
| <code>@Min(1) Integer page</code> |
| <code>@Min(1) @Max(100) Integer size</code> |
| <code>@Size(max=32) String areaNo</code> |
| <code>@Pattern(regexp="ACTIVE&#124;DISABLED") String status</code> |
| <code>@Pattern(regexp="IDLE&#124;IN_USE&#124;NEEDS_CLEANING") String turnoverState</code> |
| <code>@Size(max=64) String keyword</code> |

## ai.neargo.shop.food.dto.MeasurementPolicy

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MeasurementPolicy.java)

### MeasurementPolicy

| 字段声明（含校验） |
| --- |
| <code>String unit</code> |
| <code>String mode</code> |
| <code>Integer scale</code> |
| <code>BigDecimal allowedDeviation</code> |

## ai.neargo.shop.food.dto.MenuCatalogPriceContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuCatalogPriceContracts.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |

### PriceLine

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String mainSkuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String groupNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String choiceNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String unitsPerPick</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceRevision</code> |

### Facts

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion</code> |
| <code>Query query</code> |
| <code>MenuSourceContracts.Proof menuProof</code> |
| <code>CatalogPriceSourcePort.Facts productFacts</code> |
| <code>List<PriceLine> prices</code> |
| <code>Instant verifiedAt</code> |
| <code>Instant validUntil</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiptNo</code> |

## ai.neargo.shop.food.dto.MenuCategoryInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuCategoryInput.java)

### MenuCategoryInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String categoryNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo</code> |
| <code>List<MenuItemInput> items</code> |

## ai.neargo.shop.food.dto.MenuChannelBindingVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuChannelBindingVO.java)

### MenuChannelBindingVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelAccountRef</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.MenuCompositionResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuCompositionResultVO.java)

### MenuCompositionResultVO

| 字段声明（含校验） |
| --- |
| <code>MenuVO menu</code> |
| <code>MenuCompositionVO composition</code> |

## ai.neargo.shop.food.dto.MenuCompositionVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuCompositionVO.java)

### MenuCompositionVO

| 字段声明（含校验） |
| --- |
| <code>List<MenuCategoryInput> categories</code> |

## ai.neargo.shop.food.dto.MenuConfigResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuConfigResultVO.java)

### MenuConfigResultVO

| 字段声明（含校验） |
| --- |
| <code>MenuVO config</code> |
| <code>MenuScheduleVO schedule</code> |
| <code>MenuCompositionVO composition</code> |
| <code>List<MenuContextInput> contexts</code> |

## ai.neargo.shop.food.dto.MenuContextInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuContextInput.java)

### MenuContextInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricePolicyRef</code> |

## ai.neargo.shop.food.dto.MenuContextsResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuContextsResultVO.java)

### MenuContextsResultVO

| 字段声明（含校验） |
| --- |
| <code>MenuVO menu</code> |
| <code>List<MenuContextInput> contexts</code> |

## ai.neargo.shop.food.dto.MenuItemInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuItemInput.java)

### MenuItemInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo</code> |
| <code>@JsonDeserialize(using = MenuDefinitionPolicy.IntegerDeserializer.class) Integer sortNo</code> |
| <code>@JsonDeserialize(using = MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuScope</code> |
| <code>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> skuNos</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String optionMode</code> |
| <code>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> optionGroupNos</code> |
| <code>List<MenuSkuRecipeInput> skuRecipes</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) List<MenuSkuPriceInput> skuPrices</code> |

## ai.neargo.shop.food.dto.MenuPublicationSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuPublicationSnapshot.java)

### MenuPublicationSnapshot

| 字段声明（含校验） |
| --- |
| <code>int contentSchema</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion</code> |
| <code>Captured menu</code> |
| <code>List<SkuIdentityFact> skuFacts</code> |
| <code>List<GoodsFact> goodsFacts</code> |
| <code>String approvalRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext</code> |

### Captured

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>MenuConfigResultVO definition</code> |
| <code>List<DishVO> dishes</code> |
| <code>List<ItemOptions> itemOptions</code> |
| <code>List<Dependency> dependencies</code> |
| <code>List<MenuChannelBindingVO> channelBindings</code> |

### ItemOptions

| 字段声明（含校验） |
| --- |
| <code>String menuItemNo</code> |
| <code>List<String> optionGroupNos</code> |

### Dependency

| 字段声明（含校验） |
| --- |
| <code>String revisionNo</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision</code> |
| <code>int contentSchema</code> |
| <code>String contentHash</code> |
| <code>String definitionSnapshot</code> |

## ai.neargo.shop.food.dto.MenuResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuResultVO.java)

### MenuResultVO

| 字段声明（含校验） |
| --- |
| <code>MenuVO menu</code> |

## ai.neargo.shop.food.dto.MenuScheduleResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuScheduleResultVO.java)

### MenuScheduleResultVO

| 字段声明（含校验） |
| --- |
| <code>MenuVO menu</code> |
| <code>MenuScheduleVO schedule</code> |

## ai.neargo.shop.food.dto.MenuScheduleVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuScheduleVO.java)

### MenuScheduleVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validFrom</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo</code> |
| <code>List<WeekScheduleInput> weekSchedules</code> |
| <code>List<ExceptionInput> exceptions</code> |

## ai.neargo.shop.food.dto.MenuSkuPriceInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuSkuPriceInput.java)

### MenuSkuPriceInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long priceMinor</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency</code> |

## ai.neargo.shop.food.dto.MenuSkuRecipeInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuSkuRecipeInput.java)

### MenuSkuRecipeInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |

## ai.neargo.shop.food.dto.MenuSourceContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuSourceContracts.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@NotNull @JsonDeserialize(using = KindDeserializer.class) Kind kind</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String resourceRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String salesOfferRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String optionGroupRef</code> |
| <code>@NotNull @JsonDeserialize(using = ModeDeserializer.class) Mode mode</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String variantSkuRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String bundleGroupRef</code> |

### Resource

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = KindDeserializer.class) Kind kind</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String resourceRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String salesOfferRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String optionGroupRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String variantSkuRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String bundleGroupRef</code> |

### Proof

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion</code> |
| <code>Query query</code> |
| <code>ConfigRevisionContentVO publication</code> |
| <code>Resource resource</code> |
| <code>OffsetDateTime verifiedAt</code> |
| <code>OffsetDateTime validUntil</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiptNo</code> |

## ai.neargo.shop.food.dto.MenuVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuVO.java)

### MenuVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion</code> |

## ai.neargo.shop.food.dto.MenuWithdrawalSourceContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/MenuWithdrawalSourceContracts.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String menuNo</code> |
| <code>String previousRevision</code> |

### Data

| 字段声明（含校验） |
| --- |
| <code>Query query</code> |
| <code>long menuVersion</code> |
| <code>String previousContentHash</code> |
| <code>String actionNo</code> |
| <code>String actionHash</code> |
| <code>Instant withdrawnAt</code> |

### Disposition

| 字段声明（含校验） |
| --- |
| <code>Query query</code> |
| <code>String headStatus</code> |
| <code>long headVersion</code> |
| <code>Proof withdrawal</code> |
| <code>Instant verifiedAt</code> |
| <code>Instant validUntil</code> |
| <code>String receiptNo</code> |

### Proof

| 字段声明（含校验） |
| --- |
| <code>Data data</code> |
| <code>Instant verifiedAt</code> |
| <code>Instant validUntil</code> |
| <code>String receiptNo</code> |

## ai.neargo.shop.food.dto.ModifierComponentInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ModifierComponentInput.java)

### ModifierComponentInput

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String memberNo</code> |
| <code>@NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=9, fraction=3) BigDecimal quantity</code> |

## ai.neargo.shop.food.dto.ModifierConfigVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ModifierConfigVO.java)

### ModifierConfigVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>String kind</code> |
| <code>long version</code> |
| <code>String status</code> |
| <code>Data data</code> |

### Data

| 字段声明（含校验） |
| --- |
| <code>String name</code> |
| <code>String salesSkuNo</code> |
| <code>String publishedRevisionNo</code> |
| <code>List<Component> components</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) String inventoryMode</code> |

### Component

| 字段声明（含校验） |
| --- |
| <code>String componentNo</code> |
| <code>String memberNo</code> |
| <code>BigDecimal quantity</code> |

## ai.neargo.shop.food.dto.ModifierMutationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ModifierMutationVO.java)

### ModifierMutationVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.OccupancyVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OccupancyVO.java)

### OccupancyVO

| 字段声明（含校验） |
| --- |
| <code>String occupancyNo</code> |
| <code>String tableNo</code> |
| <code>String checkNo</code> |
| <code>int seats</code> |
| <code>Instant startedAt</code> |

## ai.neargo.shop.food.dto.OfflineDraftContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OfflineDraftContracts.java)

### ChallengeRequest

| 字段声明（含校验） |
| --- |
| <code>String publicKey</code> |
| <code>String credential</code> |

### Challenge

| 字段声明（含校验） |
| --- |
| <code>String challengeNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>long deviceGeneration</code> |
| <code>String publicKeyHash</code> |
| <code>String nonce</code> |
| <code>Instant validUntil</code> |

### Enroll

| 字段声明（含校验） |
| --- |
| <code>String challengeNo</code> |
| <code>String signature</code> |
| <code>String credential</code> |

### Signer

| 字段声明（含校验） |
| --- |
| <code>String keyNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>long deviceGeneration</code> |
| <code>String publicKeyHash</code> |

### LeaseRequest

| 字段声明（含校验） |
| --- |
| <code>String keyNo</code> |
| <code>long expectedFence</code> |
| <code>int lifetimeSeconds</code> |
| <code>String requestKey</code> |
| <code>String signature</code> |

### LeaseProof

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>long deviceGeneration</code> |
| <code>String keyNo</code> |
| <code>long expectedFence</code> |
| <code>int lifetimeSeconds</code> |
| <code>String requestKey</code> |

### Lease

| 字段声明（含校验） |
| --- |
| <code>String leaseNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>long deviceGeneration</code> |
| <code>String keyNo</code> |
| <code>long fence</code> |
| <code>Instant validUntil</code> |
| <code>long acceptedThrough</code> |
| <code>String capability</code> |

### Envelope

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>long deviceGeneration</code> |
| <code>String keyNo</code> |
| <code>String leaseNo</code> |
| <code>long fence</code> |
| <code>long sequence</code> |
| <code>String fulfillment</code> |
| <code>List<OrderLineInput> lines</code> |

### Upload

| 字段声明（含校验） |
| --- |
| <code>Envelope envelope</code> |
| <code>String signature</code> |

### Replay

| 字段声明（含校验） |
| --- |
| <code>String draftNo</code> |
| <code>CartAccess access</code> |

### Receipt

| 字段声明（含校验） |
| --- |
| <code>String receiptNo</code> |
| <code>String draftNo</code> |
| <code>String status</code> |
| <code>long acceptedThrough</code> |
| <code>String payloadHash</code> |
| <code>String cartNo</code> |
| <code>int appliedLines</code> |
| <code>String reason</code> |

## ai.neargo.shop.food.dto.OpenCheckReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OpenCheckReq.java)

### OpenCheckReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Size(max=100) List<@NotBlank @Size(max=32) String> tableNos</code> |
| <code>@NotNull @Min(1) Integer partySize</code> |
| <code>@NotBlank String payMode</code> |
| <code>@Size(max=32) String reservationNo</code> |
| <code>@NotNull @Valid @Size(max=100) List<TableVersionReq> tableVersions</code> |
| <code>@NotBlank String sharingMode</code> |
| <code>@NotNull @Valid @Size(max=1000) List<SeatReq> seats</code> |
| <code>@Size(max=32) String serviceEmployeeNo</code> |

## ai.neargo.shop.food.dto.OpenSharedCheckReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OpenSharedCheckReq.java)

### OpenSharedCheckReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Min(1) Integer partySize</code> |
| <code>@NotBlank String payMode</code> |

## ai.neargo.shop.food.dto.OperationResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OperationResultVO.java)

### OperationResultVO

| 字段声明（含校验） |
| --- |
| <code>OperationVO operation</code> |

## ai.neargo.shop.food.dto.OperationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OperationVO.java)

### OperationVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String status</code> |
| <code>long version</code> |
| <code>String resultRef</code> |

## ai.neargo.shop.food.dto.OptionGroupConfigVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionGroupConfigVO.java)

### OptionGroupConfigVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>String kind</code> |
| <code>long revision</code> |
| <code>String status</code> |
| <code>Data data</code> |

### Data

| 字段声明（含校验） |
| --- |
| <code>String name</code> |
| <code>BigDecimal minQty</code> |
| <code>BigDecimal maxQty</code> |
| <code>String publishedRevisionNo</code> |
| <code>Map<String, Object> selectionRules</code> |
| <code>List<Item> items</code> |

### Item

| 字段声明（含校验） |
| --- |
| <code>String optionItemNo</code> |
| <code>String sourceKind</code> |
| <code>String specValueNo</code> |
| <code>String modifierNo</code> |
| <code>BigDecimal minQty</code> |
| <code>BigDecimal maxQty</code> |
| <code>BigDecimal defaultQty</code> |
| <code>int sortNo</code> |

## ai.neargo.shop.food.dto.OptionGroupMutationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionGroupMutationVO.java)

### OptionGroupMutationVO

| 字段声明（含校验） |
| --- |
| <code>String configNo</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.OptionGroupResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionGroupResultVO.java)

### OptionGroupResultVO

| 字段声明（含校验） |
| --- |
| <code>OptionGroupConfigVO config</code> |

## ai.neargo.shop.food.dto.OptionItemInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OptionItemInput.java)

### OptionItemInput

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String optionItemNo</code> |
| <code>@NotBlank String sourceKind</code> |
| <code>@Size(max=64) String specValueNo</code> |
| <code>@Size(max=32) String modifierNo</code> |
| <code>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal minQty</code> |
| <code>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal maxQty</code> |
| <code>@NotNull @DecimalMin("0") @Digits(integer=12, fraction=6) BigDecimal defaultQty</code> |
| <code>@NotNull @Min(0) Integer sortNo</code> |

## ai.neargo.shop.food.dto.OrderLineInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OrderLineInput.java)

### OrderLineInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuItemNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuRevisionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String goodsNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String seatNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String courseNo</code> |
| <code>List<CartSelectionInput> selections</code> |
| <code>List<CartBundleChoiceInput> bundleChoices</code> |

## ai.neargo.shop.food.dto.PayDiningSettlementReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PayDiningSettlementReq.java)

### PayDiningSettlementReq

| 字段声明（含校验） |
| --- |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version</code> |
| <code>@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest</code> |
| <code>@NotBlank @Size(max = 32) String payChannel</code> |

## ai.neargo.shop.food.dto.PrintDocumentReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PrintDocumentReq.java)

### PrintDocumentReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String scene</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo</code> |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo</code> |

## ai.neargo.shop.food.dto.ProductionContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java)

### ListQuery

| 字段声明（含校验） |
| --- |
| <code>Integer page</code> |
| <code>Integer size</code> |
| <code>LocalDate from</code> |
| <code>LocalDate to</code> |
| <code>String skuNo</code> |
| <code>String itemRef</code> |
| <code>String status</code> |

### PendingQuery

| 字段声明（含校验） |
| --- |
| <code>@jakarta.validation.constraints.Min(0) long afterId</code> |
| <code>@jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int limit</code> |

### PendingVO

| 字段声明（含校验） |
| --- |
| <code>long sourceId</code> |
| <code>String sourceEventNo</code> |
| <code>String taskNo</code> |
| <code>String action</code> |
| <code>String status</code> |

### AdjustmentReq

| 字段声明（含校验） |
| --- |
| <code>Long version</code> |
| <code>String kind</code> |
| <code>BigDecimal qty</code> |
| <code>String reason</code> |

### ReverseReq

| 字段声明（含校验） |
| --- |
| <code>Long version</code> |
| <code>String reason</code> |
| <code>String disposition</code> |
| <code>String reasonCode</code> |

### LineVO

| 字段声明（含校验） |
| --- |
| <code>int lineNo</code> |
| <code>String componentPath</code> |
| <code>String itemRef</code> |
| <code>BigDecimal theoryQty</code> |
| <code>String unit</code> |
| <code>String recipeRevisionNo</code> |
| <code>String inventoryReceiptRef</code> |
| <code>BigDecimal inventoryIssuedQty</code> |
| <code>Long costMinor</code> |
| <code>String currency</code> |
| <code>String status</code> |

### ConsumptionVO

| 字段声明（含校验） |
| --- |
| <code>String consumptionNo</code> |
| <code>String sourceEventNo</code> |
| <code>String purpose</code> |
| <code>String status</code> |
| <code>String requestHash</code> |
| <code>String inventoryOperationRef</code> |
| <code>LocalDate businessDate</code> |
| <code>LocalDateTime occurredAt</code> |
| <code>List<LineVO> lines</code> |
| <code>String quantityEvidence</code> |
| <code>boolean consumptionPending</code> |
| <code>Long totalCostMinor</code> |
| <code>String costCurrency</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.PublicationMarketContext

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublicationMarketContext.java)

### PublicationMarketContext

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String market</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String currency</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer currencyScale</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String contextHash</code> |
| <code>@JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String marketReceiptNo</code> |

## ai.neargo.shop.food.dto.PublicationSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublicationSnapshot.java)

### PublicationSnapshot

| 字段声明（含校验） |
| --- |
| <code>int contentSchema</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion</code> |
| <code>ModifierConfigVO modifier</code> |
| <code>OptionGroupConfigVO optionGroup</code> |
| <code>List<Dependency> dependencies</code> |
| <code>List<SkuIdentityFact> skuFacts</code> |
| <code>List<SpecValueFact> specFacts</code> |
| <code>String approvalRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext</code> |

### Dependency

| 字段声明（含校验） |
| --- |
| <code>String revisionNo</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision</code> |
| <code>String contentHash</code> |

## ai.neargo.shop.food.dto.PublishConfigReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublishConfigReq.java)

### PublishConfigReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull OffsetDateTime effectiveAt</code> |
| <code>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef</code> |

## ai.neargo.shop.food.dto.PublishConfigResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PublishConfigResultVO.java)

### PublishConfigResultVO

| 字段声明（含校验） |
| --- |
| <code>ConfigRevisionVO publication</code> |

## ai.neargo.shop.food.dto.QueueContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java)

### IssueReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(1) @Max(1000) Integer partySize</code> |
| <code>@Size(max=64) String contactRef</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>@NotNull LocalDate businessDate</code> |
| <code>String status</code> |
| <code>Integer page</code> |
| <code>Integer size</code> |

### ChangeReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@Size(max=255) String reason</code> |

### SeatQueueReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Valid OpenCheckReq check</code> |

### QueueVO

| 字段声明（含校验） |
| --- |
| <code>String ticketNo</code> |
| <code>LocalDate businessDate</code> |
| <code>int partySize</code> |
| <code>String status</code> |
| <code>LocalDateTime calledAt</code> |
| <code>String checkNo</code> |
| <code>long version</code> |
| <code>String notificationStatus</code> |

## ai.neargo.shop.food.dto.RecipeAdjustmentInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeAdjustmentInput.java)

### RecipeAdjustmentInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String adjustmentNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionItemNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String targetLineNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String action</code> |
| <code>BigDecimal qty</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit</code> |

## ai.neargo.shop.food.dto.RecipeAdjustmentVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeAdjustmentVO.java)

### RecipeAdjustmentVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String adjustmentNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionItemNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String targetLineNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String action</code> |
| <code>BigDecimal qty</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String optionIdentityHash</code> |

## ai.neargo.shop.food.dto.RecipeLineInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeLineInput.java)

### RecipeLineInput

| 字段声明（含校验） |
| --- |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef</code> |
| <code>@NotNull BigDecimal qty</code> |
| <code>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit</code> |
| <code>@NotNull BigDecimal lossRate</code> |

## ai.neargo.shop.food.dto.RecipeLineVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeLineVO.java)

### RecipeLineVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef</code> |
| <code>BigDecimal qty</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit</code> |
| <code>BigDecimal lossRate</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String materialDefinitionHash</code> |

## ai.neargo.shop.food.dto.RecipeOptionGroupSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeOptionGroupSnapshot.java)

### RecipeOptionGroupSnapshot

| 字段声明（含校验） |
| --- |
| <code>String goodsNo</code> |
| <code>Dependency publication</code> |
| <code>PublicationSnapshot definition</code> |

## ai.neargo.shop.food.dto.RecipePublicationSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipePublicationSnapshot.java)

### RecipePublicationSnapshot

| 字段声明（含校验） |
| --- |
| <code>int contentSchema</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion</code> |
| <code>RecipeVO recipe</code> |
| <code>List<SkuIdentityFact> skuFacts</code> |
| <code>List<MaterialFact> materialFacts</code> |
| <code>List<RecipeOptionGroupSnapshot> optionGroups</code> |
| <code>String approvalRef</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext</code> |

## ai.neargo.shop.food.dto.RecipeResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeResultVO.java)

### RecipeResultVO

| 字段声明（含校验） |
| --- |
| <code>RecipeVO recipe</code> |

## ai.neargo.shop.food.dto.RecipeVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RecipeVO.java)

### RecipeVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode</code> |
| <code>BigDecimal yieldQty</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion</code> |
| <code>List<RecipeLineVO> lines</code> |
| <code>List<RecipeAdjustmentVO> adjustments</code> |

## ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceBundleDefinitionReq.java)

### ReplaceBundleDefinitionReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull @Size(max=30) List<@NotNull @Valid BundleGroupInput> groups</code> |
| <code>@NotNull @Size(max=100) List<@NotNull @Valid BundleChoiceInput> choices</code> |

## ai.neargo.shop.food.dto.ReplaceCartLineReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceCartLineReq.java)

### ReplaceCartLineReq

| 字段声明（含校验） |
| --- |
| <code>OrderLineInput line</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.ReplaceMenuCompositionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuCompositionReq.java)

### ReplaceMenuCompositionReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section</code> |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull @Size(max=30) List<MenuCategoryInput> categories</code> |

## ai.neargo.shop.food.dto.ReplaceMenuContextsReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuContextsReq.java)

### ReplaceMenuContextsReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section</code> |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull @Size(max=50) List<@Valid MenuContextInput> contexts</code> |

## ai.neargo.shop.food.dto.ReplaceMenuScheduleReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceMenuScheduleReq.java)

### ReplaceMenuScheduleReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section</code> |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull @Size(max=100) List<@NotNull @Valid WeekScheduleInput> weekSchedules</code> |
| <code>@NotNull @Size(max=366) List<@NotNull @Valid ExceptionInput> exceptions</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validFrom</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo</code> |

## ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceOptionGroupItemsReq.java)

### ReplaceOptionGroupItemsReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Size(max=100) List<@Valid OptionItemInput> items</code> |
| <code>@NotNull @Size(max=100) List<String> goodsNos</code> |
| <code>@NotNull @Valid SelectionRules selectionRules</code> |

## ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReplaceRecipeDefinitionReq.java)

### ReplaceRecipeDefinitionReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull BigDecimal yieldQty</code> |
| <code>@NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit</code> |
| <code>@NotNull @Size(max=100) List<@NotNull @Valid RecipeLineInput> lines</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=100) List<@NotNull @Valid RecipeAdjustmentInput> adjustments</code> |

## ai.neargo.shop.food.dto.RepublishConfigReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RepublishConfigReq.java)

### RepublishConfigReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull OffsetDateTime effectiveAt</code> |
| <code>@NotBlank @Size(max=255) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef</code> |

## ai.neargo.shop.food.dto.ReservationContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReservationContracts.java)

### CreateReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(1) Integer partySize</code> |
| <code>@NotNull Instant startAt</code> |
| <code>@NotNull Instant endAt</code> |
| <code>@NotBlank @Size(max=64) String contactRef</code> |
| <code>@NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables</code> |

### ChangeReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Valid CreateReq appointment</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

### TransitionReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

### ArriveReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables</code> |

### DetailVO

| 字段声明（含校验） |
| --- |
| <code>ReservationVO reservation</code> |
| <code>List<String> tableNos</code> |

## ai.neargo.shop.food.dto.ReservationPreorderContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReservationPreorderContracts.java)

### SaveReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long reservationVersion</code> |
| <code>@NotNull Instant cutoffAt</code> |
| <code>@NotEmpty @Size(max = 100) List<@Valid OrderLineInput> lines</code> |

### PreorderVO

| 字段声明（含校验） |
| --- |
| <code>String preorderNo</code> |
| <code>String reservationNo</code> |
| <code>String status</code> |
| <code>Instant cutoffAt</code> |
| <code>List<OrderLineInput> lines</code> |
| <code>String cartNo</code> |
| <code>String batchNo</code> |
| <code>long version</code> |

### ConvertReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@NotNull @Valid CreateStaffDiningOrderReq order</code> |

## ai.neargo.shop.food.dto.ReservationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ReservationVO.java)

### ReservationVO

| 字段声明（含校验） |
| --- |
| <code>String reservationNo</code> |
| <code>int partySize</code> |
| <code>Instant startAt</code> |
| <code>Instant endAt</code> |
| <code>String status</code> |
| <code>String depositRef</code> |
| <code>String checkNo</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.RevokeTableTokenReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RevokeTableTokenReq.java)

### RevokeTableTokenReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.RotateTableTokenReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/RotateTableTokenReq.java)

### RotateTableTokenReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull Instant expiresAt</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.SalesQuotaClaimSource

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesQuotaClaimSource.java)

### SalesQuotaClaimSource

| 字段声明（含校验） |
| --- |
| <code>String sourceRef</code> |
| <code>String skuNo</code> |
| <code>BigDecimal quantity</code> |

## ai.neargo.shop.food.dto.SalesQuotaClaimVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesQuotaClaimVO.java)

### SalesQuotaClaimVO

| 字段声明（含校验） |
| --- |
| <code>String claimNo</code> |
| <code>String restrictionNo</code> |
| <code>String batchNo</code> |
| <code>BigDecimal quantity</code> |
| <code>String status</code> |

## ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionEvaluationVO.java)

### SalesRestrictionEvaluationVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean blocked</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean originalStockKnown</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf</code> |
| <code>List<SalesRestrictionVO> restrictions</code> |

## ai.neargo.shop.food.dto.SalesRestrictionQuery

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionQuery.java)

### SalesRestrictionQuery

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |

## ai.neargo.shop.food.dto.SalesRestrictionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionReq.java)

### SalesRestrictionReq

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.OptionalVersionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota</code> |
| <code>LocalDate businessDate</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |

## ai.neargo.shop.food.dto.SalesRestrictionVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SalesRestrictionVO.java)

### SalesRestrictionVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode</code> |
| <code>LocalDate businessDate</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal reservedQty</code> |
| <code>@JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal consumedQty</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt</code> |
| <code>@JsonFormat(shape=JsonFormat.Shape.STRING) @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.SaveCalendarReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveCalendarReq.java)

### SaveCalendarReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Pattern(regexp = "OPEN&#124;CLOSED") String mode</code> |
| <code>@NotNull @Size(max = 32) List<@NotBlank String> intervals</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.SaveCheckSeatsReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveCheckSeatsReq.java)

### SaveCheckSeatsReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotNull @Min(1) Integer partySize</code> |
| <code>@NotNull @Valid @Size(max=1000) List<SeatReq> seats</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.SaveDishReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveDishReq.java)

### SaveDishReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Pattern(regexp="SINGLE&#124;BUNDLE&#124;ADDON") String dishKind</code> |
| <code>@NotNull @Min(0) Integer prepSeconds</code> |
| <code>@Valid @NotNull DishPresentation presentation</code> |
| <code>@Valid @NotNull MeasurementPolicy measurement</code> |
| <code>@Min(0) Long version</code> |

## ai.neargo.shop.food.dto.SaveTableAreaReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveTableAreaReq.java)

### SaveTableAreaReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull Integer sortNo</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.SaveTableReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SaveTableReq.java)

### SaveTableReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String areaNo</code> |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotNull @Min(1) Integer capacity</code> |
| <code>@NotNull Integer sortNo</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.SeatReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SeatReq.java)

### SeatReq

| 字段声明（含校验） |
| --- |
| <code>@Size(max=32) String seatNo</code> |
| <code>@NotBlank @Size(max=32) String label</code> |
| <code>@Size(max=32) String guestRef</code> |

## ai.neargo.shop.food.dto.SelectionRules

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SelectionRules.java)

### SelectionRules

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Size(max=100) List<List<String>> mutexSets</code> |
| <code>@NotNull @Size(max=100) List<@Valid SelectionDependency> requires</code> |

### SelectionDependency

| 字段声明（含校验） |
| --- |
| <code>String optionItemNo</code> |
| <code>List<String> requiresItemNos</code> |

## ai.neargo.shop.food.dto.ServiceEntitlementContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ServiceEntitlementContracts.java)

### QuoteRef

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String cartNo</code> |
| <code>@Min(0) long cartVersion</code> |
| <code>@NotBlank String quoteNo</code> |
| <code>@NotBlank String confirmationDigest</code> |

### Grant

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long formVersion</code> |
| <code>@NotBlank String attendeeNo</code> |
| <code>@NotBlank String menuItemNo</code> |
| <code>@NotBlank String goodsNo</code> |
| <code>@NotBlank String skuNo</code> |
| <code>@NotNull BigDecimal quantity</code> |
| <code>@Valid @NotNull QuoteRef quote</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### Consume

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long entitlementVersion</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String foodLineNo</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### Entitlement

| 字段声明（含校验） |
| --- |
| <code>String entitlementNo</code> |
| <code>String formNo</code> |
| <code>String attendeeNo</code> |
| <code>String goodsNo</code> |
| <code>String skuNo</code> |
| <code>BigDecimal granted</code> |
| <code>BigDecimal consumed</code> |
| <code>String unit</code> |
| <code>Instant validFrom</code> |
| <code>Instant validUntil</code> |
| <code>String quoteNo</code> |
| <code>String sourceHash</code> |
| <code>long version</code> |

### Extension

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long formVersion</code> |
| <code>@NotNull Instant validUntil</code> |
| <code>@NotBlank String capacitySlotNo</code> |
| <code>@Valid @NotNull QuoteRef quote</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### ExtensionReceipt

| 字段声明（含校验） |
| --- |
| <code>String eventNo</code> |
| <code>String formNo</code> |
| <code>Instant beforeUntil</code> |
| <code>Instant validUntil</code> |
| <code>String capacityClaimNo</code> |
| <code>String quoteNo</code> |
| <code>long formVersion</code> |

### Dish

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String menuItemNo</code> |
| <code>@NotBlank String goodsNo</code> |
| <code>@NotBlank String skuNo</code> |
| <code>@NotNull BigDecimal quantity</code> |
| <code>@NotBlank String unit</code> |

### Plan

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long formVersion</code> |
| <code>@Min(1) @Max(1000) int plannedPersons</code> |
| <code>@Min(0) long intendedBudgetMinor</code> |
| <code>@NotNull @Size(min = 1, max = 100) List<@Valid Dish> dishes</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### PlanRevision

| 字段声明（含校验） |
| --- |
| <code>String planNo</code> |
| <code>String formNo</code> |
| <code>long revision</code> |
| <code>int plannedPersons</code> |
| <code>String currency</code> |
| <code>long intendedBudgetMinor</code> |
| <code>List<Dish> dishes</code> |
| <code>String contentHash</code> |
| <code>long formVersion</code> |

### ConfirmAttendance

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long formVersion</code> |
| <code>@NotBlank String planNo</code> |
| <code>@Valid @NotNull QuoteRef quote</code> |
| <code>String depositRef</code> |
| <code>@NotBlank @Size(max = 255) String reason</code> |

### AttendanceConfirmation

| 字段声明（含校验） |
| --- |
| <code>String confirmationNo</code> |
| <code>String formNo</code> |
| <code>String planNo</code> |
| <code>int actualPersons</code> |
| <code>String rosterHash</code> |
| <code>String quoteNo</code> |
| <code>String quoteHash</code> |
| <code>String currency</code> |
| <code>long calculatedReceivableMinor</code> |
| <code>long formVersion</code> |

## ai.neargo.shop.food.dto.ServiceFormContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ServiceFormContracts.java)

### OrganizationReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String name</code> |
| <code>@NotBlank String contactRef</code> |
| <code>@NotBlank String externalRef</code> |

### OrganizationVO

| 字段声明（含校验） |
| --- |
| <code>String organizationNo</code> |
| <code>String name</code> |
| <code>String contactRef</code> |
| <code>String externalRef</code> |
| <code>String status</code> |

### BookReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull Kind kind</code> |
| <code>String reservationNo</code> |
| <code>String slotNo</code> |
| <code>String menuNo</code> |
| <code>String menuRevisionNo</code> |
| <code>@Min(1) int guaranteedPersons</code> |
| <code>@NotNull Instant startsAt</code> |
| <code>@NotNull Instant endsAt</code> |
| <code>String organizationNo</code> |
| <code>String contactRef</code> |
| <code>@Min(1) @Max(100) int maxEntriesPerPerson</code> |
| <code>@NotBlank String reason</code> |

### Guest

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 64) String guestRef</code> |
| <code>String seatNo</code> |
| <code>@NotBlank String personClass</code> |

### EnterReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@Min(0) long checkVersion</code> |
| <code>@NotEmpty @Size(max = 1000) List<@Valid Guest> guests</code> |
| <code>@NotBlank String reason</code> |

### ReviseReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@Min(1) int guaranteedPersons</code> |
| <code>String menuNo</code> |
| <code>String menuRevisionNo</code> |
| <code>@NotBlank String reason</code> |

### VersionReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@NotBlank String reason</code> |

### BatchReq

| 字段声明（含校验） |
| --- |
| <code>@Min(0) long version</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String reason</code> |

### FormVO

| 字段声明（含校验） |
| --- |
| <code>String formNo</code> |
| <code>Kind kind</code> |
| <code>String status</code> |
| <code>String reservationNo</code> |
| <code>String slotNo</code> |
| <code>String menuNo</code> |
| <code>String menuRevisionNo</code> |
| <code>int guaranteedPersons</code> |
| <code>Integer actualPersons</code> |
| <code>String checkNo</code> |
| <code>Instant startsAt</code> |
| <code>Instant endsAt</code> |
| <code>String organizationNo</code> |
| <code>String contactRef</code> |
| <code>int maxEntriesPerPerson</code> |
| <code>String sourceTransition</code> |
| <code>long version</code> |

### AttendeeVO

| 字段声明（含校验） |
| --- |
| <code>String attendeeNo</code> |
| <code>String guestRef</code> |
| <code>String seatNo</code> |
| <code>String personClass</code> |
| <code>String status</code> |
| <code>int entryCount</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.ServiceSlotContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ServiceSlotContracts.java)

### CreateReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull Instant startsAt</code> |
| <code>@NotNull Instant endsAt</code> |
| <code>@NotNull Instant orderCutoffAt</code> |
| <code>@NotNull Instant dispatchAt</code> |
| <code>@NotNull @DecimalMin("0.000001") BigDecimal capacity</code> |
| <code>@NotBlank String unit</code> |

### SlotVO

| 字段声明（含校验） |
| --- |
| <code>String slotNo</code> |
| <code>Instant startsAt</code> |
| <code>Instant endsAt</code> |
| <code>Instant orderCutoffAt</code> |
| <code>Instant dispatchAt</code> |
| <code>BigDecimal capacity</code> |
| <code>BigDecimal reserved</code> |
| <code>BigDecimal consumed</code> |
| <code>String unit</code> |
| <code>String status</code> |
| <code>long version</code> |

### ClaimReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String slotNo</code> |
| <code>@NotBlank String sourceNo</code> |
| <code>@NotNull @DecimalMin("0.000001") BigDecimal quantity</code> |

### ClaimVO

| 字段声明（含校验） |
| --- |
| <code>String claimNo</code> |
| <code>String slotNo</code> |
| <code>String sourceNo</code> |
| <code>BigDecimal quantity</code> |
| <code>String status</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.SetStatusTableAreaReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SetStatusTableAreaReq.java)

### SetStatusTableAreaReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Pattern(regexp="ACTIVE&#124;DISABLED") String status</code> |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.SetStatusTableReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/SetStatusTableReq.java)

### SetStatusTableReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Pattern(regexp="ACTIVE&#124;DISABLED") String status</code> |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |

## ai.neargo.shop.food.dto.StartDiningSessionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StartDiningSessionReq.java)

### StartDiningSessionReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=128) String tableToken</code> |
| <code>@Size(max=512) String deviceCredential</code> |

## ai.neargo.shop.food.dto.StationContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StationContracts.java)

### CreateStationReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType</code> |

### UpdateStationReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType</code> |
| <code>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean enabled</code> |

### StationRouteInput

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo</code> |
| <code>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String taskRole</code> |

### ReplaceStationRoutesReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@NotNull @Size(max=100) List<@Valid StationRouteInput> routes</code> |

### StationVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationType</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) boolean enabled</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String publishedRevisionNo</code> |

### StationMutationVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |

### GetStationVO

| 字段声明（含校验） |
| --- |
| <code>StationVO config</code> |

### StationRoutesVO

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version</code> |
| <code>List<StationRouteInput> routes</code> |

## ai.neargo.shop.food.dto.StationPublicationSnapshot

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StationPublicationSnapshot.java)

### StationPublicationSnapshot

| 字段声明（含校验） |
| --- |
| <code>int contentSchema</code> |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>long sourceVersion</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>StationVO station</code> |
| <code>List<Route> routes</code> |
| <code>List<SkuIdentityFact> skuFacts</code> |
| <code>String approvalRef</code> |

### Route

| 字段声明（含校验） |
| --- |
| <code>String skuNo</code> |
| <code>String taskRole</code> |
| <code>long routeVersion</code> |

## ai.neargo.shop.food.dto.StoreCalendarSourceContracts

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StoreCalendarSourceContracts.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using = DateDeserializer.class) LocalDate fromDate</code> |
| <code>@JsonDeserialize(using = DateDeserializer.class) LocalDate toDate</code> |

### Store

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String openHours</code> |

### Policy

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String policyNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String timezone</code> |
| <code>@JsonDeserialize(using = IntegerDeserializer.class) Integer cutoverMinute</code> |
| <code>Instant effectiveFrom</code> |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version</code> |

### ExceptionDay

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String calendarNo</code> |
| <code>@JsonDeserialize(using = DateDeserializer.class) LocalDate businessDate</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String mode</code> |
| <code>@JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> intervals</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String savedPolicyNo</code> |
| <code>@JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version</code> |

### Facts

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = IntegerDeserializer.class) Integer schemaVersion</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String purpose</code> |
| <code>Query query</code> |
| <code>Store store</code> |
| <code>List<Policy> policies</code> |
| <code>List<ExceptionDay> exceptions</code> |
| <code>Instant verifiedAt</code> |
| <code>Instant validUntil</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String receiptNo</code> |

## ai.neargo.shop.food.dto.StoreOperationPolicyResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/StoreOperationPolicyResultVO.java)

### StoreOperationPolicyResultVO

| 字段声明（含校验） |
| --- |
| <code>String policyNo</code> |
| <code>String timezone</code> |
| <code>int cutoverMinute</code> |
| <code>String payPolicy</code> |
| <code>List<String> capabilities</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.TableAreaResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableAreaResultVO.java)

### TableAreaResultVO

| 字段声明（含校验） |
| --- |
| <code>TableAreaVO area</code> |

## ai.neargo.shop.food.dto.TableAreaVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableAreaVO.java)

### TableAreaVO

| 字段声明（含校验） |
| --- |
| <code>String areaNo</code> |
| <code>String storeNo</code> |
| <code>String code</code> |
| <code>String name</code> |
| <code>int sortNo</code> |
| <code>String status</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.TableResultVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableResultVO.java)

### TableResultVO

| 字段声明（含校验） |
| --- |
| <code>TableVO table</code> |

## ai.neargo.shop.food.dto.TableTokenVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableTokenVO.java)

### TableTokenVO

| 字段声明（含校验） |
| --- |
| <code>String token</code> |
| <code>Instant expiresAt</code> |

## ai.neargo.shop.food.dto.TableVersionReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableVersionReq.java)

### TableVersionReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotNull @Min(0) Long version</code> |

## ai.neargo.shop.food.dto.TableVO

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TableVO.java)

### TableVO

| 字段声明（含校验） |
| --- |
| <code>String tableNo</code> |
| <code>String storeNo</code> |
| <code>String areaNo</code> |
| <code>String code</code> |
| <code>String name</code> |
| <code>int capacity</code> |
| <code>int sortNo</code> |
| <code>String status</code> |
| <code>String turnoverState</code> |
| <code>String resourceNo</code> |
| <code>long version</code> |

## ai.neargo.shop.food.dto.TransferCheckReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/TransferCheckReq.java)

### TransferCheckReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@NotBlank @Size(max=255) String reason</code> |
| <code>@Size(max=64) String approvalRef</code> |
| <code>@NotEmpty @Size(max=100) List<@NotBlank @Size(max=32) String> targetTableNos</code> |
| <code>@NotEmpty @Valid @Size(max=200) List<TableVersionReq> tableVersions</code> |

## ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateMenuChannelBindingReq.java)

### UpdateMenuChannelBindingReq

| 字段声明（含校验） |
| --- |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String externalStoreRef</code> |

## ai.neargo.shop.food.dto.UpdateMenuReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateMenuReq.java)

### UpdateMenuReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name</code> |
| <code>@NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType</code> |
| <code>@NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone</code> |
| <code>@NotNull @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority</code> |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup</code> |
| <code>@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version</code> |

## ai.neargo.shop.food.dto.UpdateModifierReq

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/UpdateModifierReq.java)

### UpdateModifierReq

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=64) String name</code> |
| <code>@NotBlank @Size(max=16) String kind</code> |
| <code>@Size(max=32) String salesSkuNo</code> |
| <code>@NotNull @Size(max=100) List<@NotNull @Valid ModifierComponentInput> components</code> |
| <code>@NotNull @Min(0) Long version</code> |
| <code>@JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=16) String inventoryMode</code> |

## ai.neargo.shop.food.dto.WeekScheduleInput

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/WeekScheduleInput.java)

### WeekScheduleInput

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer weekday</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String startTime</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String endTime</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer endDayOffset</code> |

## ai.neargo.shop.food.policy.FoodLocalStockBalance

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/FoodLocalStockBalance.java)

### FoodLocalStockBalance

| 字段声明（含校验） |
| --- |
| <code>BigDecimal onHand</code> |
| <code>BigDecimal reserved</code> |

## ai.neargo.shop.food.policy.FoodLocalStockReservationProgress

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/FoodLocalStockReservationProgress.java)

### FoodLocalStockReservationProgress

| 字段声明（含校验） |
| --- |
| <code>BigDecimal original</code> |
| <code>BigDecimal issued</code> |
| <code>BigDecimal released</code> |

### Change

| 字段声明（含校验） |
| --- |
| <code>FoodLocalStockBalance balance</code> |
| <code>FoodLocalStockReservationProgress progress</code> |
| <code>BigDecimal delta</code> |

## ai.neargo.shop.food.policy.MenuCatalogPricePolicy

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuCatalogPricePolicy.java)

### Identity

| 字段声明（含校验） |
| --- |
| <code>String publication</code> |
| <code>CatalogPriceSourcePort.Market market</code> |
| <code>String item</code> |
| <code>String main</code> |
| <code>String group</code> |
| <code>String choice</code> |
| <code>String kind</code> |
| <code>String sku</code> |
| <code>String quantity</code> |
| <code>long amount</code> |
| <code>List<String> basis</code> |
| <code>String tax</code> |

### Spec

| 字段声明（含校验） |
| --- |
| <code>MenuItemInput item</code> |
| <code>String main</code> |
| <code>String group</code> |
| <code>String choice</code> |
| <code>String kind</code> |
| <code>String sku</code> |
| <code>String base</code> |
| <code>BigDecimal units</code> |

## ai.neargo.shop.food.policy.MenuSelectionFeasibilityPolicy

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/MenuSelectionFeasibilityPolicy.java)

### GroupResult

| 字段声明（含校验） |
| --- |
| <code>String configKind</code> |
| <code>String configNo</code> |
| <code>String groupNo</code> |
| <code>SelectionFeasibility.Result result</code> |

### ItemResult

| 字段声明（含校验） |
| --- |
| <code>String menuItemNo</code> |
| <code>String mainSkuNo</code> |
| <code>String selectionKind</code> |
| <code>SelectionFeasibility.Status status</code> |
| <code>Integer minimumSelections</code> |
| <code>Integer maxForcedMinSelections</code> |

### Report

| 字段声明（含校验） |
| --- |
| <code>List<GroupResult> groups</code> |
| <code>List<ItemResult> items</code> |
| <code>Instant validUntil</code> |

### Key

| 字段声明（含校验） |
| --- |
| <code>String kind</code> |
| <code>String number</code> |

### BoundItem

| 字段声明（含校验） |
| --- |
| <code>String itemNo</code> |
| <code>List<String> groups</code> |
| <code>List<String> bundleSkus</code> |

## ai.neargo.shop.food.policy.PublicationMarketPolicy

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/PublicationMarketPolicy.java)

### Identity

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String market</code> |
| <code>String currency</code> |
| <code>Integer scale</code> |

## ai.neargo.shop.food.policy.SelectionFeasibility

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/SelectionFeasibility.java)

### Result

| 字段声明（含校验） |
| --- |
| <code>Status status</code> |
| <code>List<String> unreachableItemNos</code> |
| <code>long visitedNodes</code> |
| <code>Integer minimumSelections</code> |
| <code>Integer maxForcedMinSelections</code> |

### Choice

| 字段声明（含校验） |
| --- |
| <code>String id</code> |
| <code>BigDecimal min</code> |
| <code>BigDecimal max</code> |

## ai.neargo.shop.food.policy.ServiceCalendarPolicy

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/policy/ServiceCalendarPolicy.java)

### DayRule

| 字段声明（含校验） |
| --- |
| <code>LocalDate date</code> |
| <code>String mode</code> |
| <code>List<String> intervals</code> |

## ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningChannelFulfillmentSignalPort.java)

### Signal

| 字段声明（含校验） |
| --- |
| <code>Status status</code> |
| <code>DiningServiceEvent originalEvent</code> |
| <code>String importNo</code> |
| <code>String batchNo</code> |
| <code>String coreOrderNo</code> |
| <code>String custodyOperationNo</code> |
| <code>String sourceRevision</code> |
| <code>Instant observedAt</code> |
| <code>Instant validUntil</code> |
| <code>String originalReceiptRef</code> |

## ai.neargo.shop.food.service.DiningChannelOrderService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningChannelOrderService.java)

### Binding

| 字段声明（含校验） |
| --- |
| <code>Key key</code> |
| <code>Reference reference</code> |
| <code>String cartNo</code> |
| <code>String batchNo</code> |
| <code>String status</code> |
| <code>String restaurantAcceptanceReceiptRef</code> |
| <code>String cancellationReceiptRef</code> |
| <code>String coreOrderNo</code> |
| <code>String coreSubOrderNo</code> |

### Result

| 字段声明（含校验） |
| --- |
| <code>Binding binding</code> |
| <code>Preparation corePreparation</code> |

### CancellationResult

| 字段声明（含校验） |
| --- |
| <code>Key key</code> |
| <code>String cancellationReceiptRef</code> |
| <code>Cancellation coreReceipt</code> |

## ai.neargo.shop.food.service.DiningTransactionRecoveryService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/DiningTransactionRecoveryService.java)

### Report

| 字段声明（含校验） |
| --- |
| <code>int claimed</code> |
| <code>int complete</code> |
| <code>int pending</code> |
| <code>int failed</code> |

## ai.neargo.shop.food.service.FoodMaintenanceService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodMaintenanceService.java)

### Report

| 字段声明（含校验） |
| --- |
| <code>int processed</code> |
| <code>int pending</code> |

## ai.neargo.shop.food.service.FoodReturnStockService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodReturnStockService.java)

### Apply

| 字段声明（含校验） |
| --- |
| <code>String returnItemNo</code> |
| <code>String productionEventNo</code> |
| <code>BigDecimal dishQty</code> |
| <code>String disposition</code> |
| <code>String reasonCode</code> |
| <code>String reason</code> |
| <code>String evidenceRef</code> |

### DispositionVO

| 字段声明（含校验） |
| --- |
| <code>String operationNo</code> |
| <code>String returnNo</code> |
| <code>String returnItemNo</code> |
| <code>String productionEventNo</code> |
| <code>BigDecimal dishQty</code> |
| <code>String disposition</code> |
| <code>String status</code> |
| <code>String stockCommandNo</code> |
| <code>String lastErrorCode</code> |

## ai.neargo.shop.food.service.FoodStockReconciliationService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodStockReconciliationService.java)

### DifferenceVO

| 字段声明（含校验） |
| --- |
| <code>String differenceNo</code> |
| <code>String itemNo</code> |
| <code>String differenceType</code> |
| <code>Long sourceVersion</code> |
| <code>Long targetVersion</code> |
| <code>String sourceSnapshot</code> |
| <code>String targetSnapshot</code> |
| <code>String resolutionStatus</code> |

### ReconciliationVO

| 字段声明（含校验） |
| --- |
| <code>String reconciliationNo</code> |
| <code>String status</code> |
| <code>long generation</code> |
| <code>long checkedCount</code> |
| <code>long differenceCount</code> |
| <code>PageData<DifferenceVO> differences</code> |
| <code>long cursorId</code> |
| <code>long endId</code> |
| <code>java.time.LocalDateTime createdAt</code> |
| <code>java.time.LocalDateTime startedAt</code> |
| <code>java.time.LocalDateTime finishedAt</code> |
| <code>String consistencyMode</code> |

## ai.neargo.shop.food.service.FoodStockSyncService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/FoodStockSyncService.java)

### ConfigVO

| 字段声明（含校验） |
| --- |
| <code>String status</code> |
| <code>long generation</code> |
| <code>long version</code> |
| <code>long backlog</code> |
| <code>String lastErrorCode</code> |

### ConfigCommand

| 字段声明（含校验） |
| --- |
| <code>boolean enabled</code> |
| <code>long version</code> |
| <code>String reason</code> |

### Preflight

| 字段声明（含校验） |
| --- |
| <code>boolean allowed</code> |
| <code>List<String> issues</code> |

### MappingCommand

| 字段声明（含校验） |
| --- |
| <code>String targetOwnerRef</code> |
| <code>String targetLocationRef</code> |
| <code>String targetItemRef</code> |
| <code>String sourceUnit</code> |
| <code>String targetUnit</code> |
| <code>long version</code> |

### MappingVO

| 字段声明（含校验） |
| --- |
| <code>String itemNo</code> |
| <code>String targetOwnerRef</code> |
| <code>String targetLocationRef</code> |
| <code>String targetItemRef</code> |
| <code>String sourceUnit</code> |
| <code>String targetUnit</code> |
| <code>long mappingRevision</code> |
| <code>String status</code> |
| <code>long version</code> |

### DeliveryVO

| 字段声明（含校验） |
| --- |
| <code>String syncNo</code> |
| <code>String itemNo</code> |
| <code>long generation</code> |
| <code>long sourceVersion</code> |
| <code>String status</code> |
| <code>int attempts</code> |
| <code>String errorCode</code> |
| <code>String ackRef</code> |

## ai.neargo.shop.food.service.QueueNotificationService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/QueueNotificationService.java)

### NoticeVO

| 字段声明（含校验） |
| --- |
| <code>String noticeNo</code> |
| <code>String ticketNo</code> |
| <code>long ticketVersion</code> |
| <code>String status</code> |
| <code>String providerRef</code> |
| <code>long version</code> |

## ai.neargo.shop.food.service.ScheduledDiningDispatchService

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/service/ScheduledDiningDispatchService.java)

### Report

| 字段声明（含校验） |
| --- |
| <code>int claimed</code> |
| <code>int completed</code> |
| <code>int pending</code> |

## ai.neargo.shop.food.spi.BottleCustodyInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BottleCustodyInternalApi.java)

### DepositCommand

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>Deposit request</code> |
| <code>String key</code> |

### WithdrawCommand

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String custodyNo</code> |
| <code>Withdraw request</code> |
| <code>String key</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String custodyNo</code> |
| <code>String customerNo</code> |
| <code>int page</code> |
| <code>int size</code> |

## ai.neargo.shop.food.spi.BundleInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BundleInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateBundleReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String skuNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceBundleDefinitionReq definition</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String skuNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListBundlesQuery query</code> |

## ai.neargo.shop.food.spi.BusinessCloseInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessCloseInternalApi.java)

### ProposeCarryCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String closeNo</code> |
| <code>@NotBlank @Size(max=32) String exceptionNo</code> |
| <code>@NotNull @Valid ProposeCarryReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ApproveCarryCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String closeNo</code> |
| <code>@NotBlank @Size(max=32) String exceptionNo</code> |
| <code>@NotNull @Valid ApproveCarryReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### OpenCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull LocalDate date</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ReportQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid PreviewReq request</code> |

### PreviewCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid PreviewReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String closeNo</code> |

### SignCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid SignReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### AdjustCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid AdjustmentReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

## ai.neargo.shop.food.spi.BusinessOperationsInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessOperationsInternalApi.java)

### ServiceFormRegisterOrganizationCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull ServiceFormContracts.OrganizationReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormBookCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull ServiceFormContracts.BookReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormGetCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String form</code> |

### ServiceFormReviseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.ReviseReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormEnterCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.EnterReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormLeaveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.VersionReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormCancelCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.VersionReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormNoShowCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.VersionReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormBindBatchCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@Valid @NotNull ServiceFormContracts.BatchReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormAttendeesCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String form</code> |

### ServiceFormAdmitCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@NotBlank String attendee</code> |
| <code>@Valid @NotNull ServiceFormContracts.VersionReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceFormExitCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String form</code> |
| <code>@NotBlank String attendee</code> |
| <code>@Valid @NotNull ServiceFormContracts.VersionReq request</code> |
| <code>@NotBlank String key</code> |

### FoodFeeSourceBindCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull FoodFeeSourceContracts.BindReq request</code> |
| <code>@NotBlank String key</code> |

### FoodFeeSourceBindingsCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String check</code> |

### QueueNotificationListCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String ticket</code> |

### QueueNotificationRecoverCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String notice</code> |
| <code>@NotBlank String key</code> |

### CheckCollaborationPreviewReceivableCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String check</code> |

### CheckCollaborationSourceHashCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String check</code> |

### CheckCollaborationReleaseServiceCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String check</code> |
| <code>long version</code> |
| <code>@NotBlank String reason</code> |
| <code>@NotBlank String key</code> |

### CheckCollaborationCurrentLineOwnerCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String foodLineNo</code> |

### CheckCollaborationMoveBatchesCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull CheckCollaborationContracts.MoveReq request</code> |
| <code>@NotBlank String key</code> |

### CheckCollaborationMoveLinesCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull CheckCollaborationContracts.LineMoveReq request</code> |
| <code>@NotBlank String key</code> |

### CheckCollaborationFreezeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String check</code> |
| <code>@Valid @NotNull CheckCollaborationContracts.FreezeReq request</code> |
| <code>@NotBlank String key</code> |

### CheckCollaborationReleaseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String freeze</code> |
| <code>long version</code> |
| <code>@NotBlank String reason</code> |
| <code>@NotBlank String key</code> |

### ReservationPreorderSaveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String reservation</code> |
| <code>@Valid @NotNull ReservationPreorderContracts.SaveReq request</code> |
| <code>@NotBlank String key</code> |

### ReservationPreorderGetCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String reservation</code> |
| <code>@NotBlank String preorder</code> |

### ReservationPreorderPrepareCartCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String reservation</code> |
| <code>@NotBlank String preorder</code> |
| <code>long version</code> |
| <code>@NotBlank String key</code> |

### ReservationPreorderConvertCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String reservation</code> |
| <code>@NotBlank String preorder</code> |
| <code>@Valid @NotNull ReservationPreorderContracts.ConvertReq request</code> |
| <code>@NotBlank String key</code> |

### ReservationPreorderCancelCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String reservation</code> |
| <code>@NotBlank String preorder</code> |
| <code>long version</code> |
| <code>@NotBlank String reason</code> |
| <code>@NotBlank String key</code> |

### ServiceSlotCreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull ServiceSlotContracts.CreateReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceSlotGetCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String slot</code> |

### ServiceSlotReserveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull ServiceSlotContracts.ClaimReq request</code> |
| <code>@NotBlank String key</code> |

### ServiceSlotTransitionCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String claim</code> |
| <code>long version</code> |
| <code>@NotBlank String action</code> |
| <code>@NotBlank String key</code> |

### ServiceSlotDispatchDueCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String sourceNo</code> |

### BusinessOperationsEvidenceCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.Source source</code> |

### BusinessOperationsProposeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.HandoverReq request</code> |
| <code>@NotBlank String key</code> |

### BusinessOperationsHandoverCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String handover</code> |

### BusinessOperationsAcceptCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String handover</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.DecisionReq request</code> |
| <code>@NotBlank String key</code> |

### BusinessOperationsCancelCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String handover</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.DecisionReq request</code> |
| <code>@NotBlank String key</code> |

### BusinessOperationsOpenCaseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.CaseReq request</code> |
| <code>@NotBlank String key</code> |

### BusinessOperationsCasesCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>String status</code> |

### BusinessOperationsRecoverCaseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String caseNo</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.DecisionReq request</code> |
| <code>@NotBlank String key</code> |

### BusinessOperationsResolveCaseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String caseNo</code> |
| <code>@Valid @NotNull BusinessOperationsContracts.DecisionReq request</code> |
| <code>@NotBlank String key</code> |

## ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessReturnApprovalInternalApi.java)

### Approve

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String returnNo</code> |
| <code>ApproveReq request</code> |
| <code>String key</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String returnNo</code> |

### Replacement

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String returnNo</code> |
| <code>ReplacementReq request</code> |
| <code>String key</code> |

## ai.neargo.shop.food.spi.BusinessReturnInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessReturnInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@Valid @NotNull CreateReq definition</code> |
| <code>@NotBlank String idempotencyKey</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String returnNo</code> |

### CancelCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String returnNo</code> |
| <code>@Valid @NotNull CancelReq definition</code> |
| <code>@NotBlank String idempotencyKey</code> |

## ai.neargo.shop.food.spi.BusinessShareInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/BusinessShareInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@Valid @NotNull CreateReq definition</code> |
| <code>@NotBlank String idempotencyKey</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@NotBlank String planNo</code> |

### ConfirmCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@NotBlank String planNo</code> |
| <code>@Valid @NotNull ConfirmReq definition</code> |
| <code>@NotBlank String idempotencyKey</code> |

### CancelCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@NotBlank String planNo</code> |
| <code>@Valid @NotNull CancelReq definition</code> |
| <code>@NotBlank String idempotencyKey</code> |

## ai.neargo.shop.food.spi.CartInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/CartInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@Valid @NotNull CreateCartReq request</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |

### ReplaceLineCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@Valid @NotNull ReplaceCartLineReq request</code> |

### ArchiveLineCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@Valid @NotNull CartVersionReq request</code> |

### ArchiveCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@Valid @NotNull CartVersionReq request</code> |

### AuthorizeCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String checkNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String actorNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@Valid @NotNull AuthorizeCartSessionReq request</code> |

### CustomerCreateCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@Valid @NotNull CartAccess access</code> |
| <code>@Valid @NotNull CreateCustomerCartReq request</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |

### CustomerIdentity

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@Valid @NotNull CartAccess access</code> |

### CustomerReplaceLineCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef</code> |
| <code>@Valid @NotNull CartAccess access</code> |
| <code>@Valid @NotNull ReplaceCartLineReq request</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |

### CustomerArchiveLineCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 64) String sourceLineRef</code> |
| <code>@Valid @NotNull CartAccess access</code> |
| <code>@Valid @NotNull CartVersionReq request</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 128) String idempotencyKey</code> |

## ai.neargo.shop.food.spi.CatalogInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/CatalogInternalApi.java)

### ScheduledQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String cartNo</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotNull @Valid CatalogQuery definition</code> |

### PublicQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |

### GuestQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken</code> |
| <code>@Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo</code> |

## ai.neargo.shop.food.spi.CheckInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/CheckInternalApi.java)

### OpenCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull OpenCheckReq request</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListChecksQuery query</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |

### OperationLookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String operationNo</code> |

### ActionsLookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |
| <code>@Valid ListCheckActionsQuery query</code> |

### SeatsCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SaveCheckSeatsReq request</code> |

### ClearCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull ClearCheckReq request</code> |

### TransferCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull TransferCheckReq request</code> |

### CombineCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String checkNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull CombineTablesReq request</code> |

### ShareCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull OpenSharedCheckReq request</code> |

## ai.neargo.shop.food.spi.ConfigRevisionInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ConfigRevisionInternalApi.java)

### PageReceipt

| 字段声明（含校验） |
| --- |
| <code>List<ConfigRevisionVO> records</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size</code> |

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid PublishConfigReq publication</code> |

### RepublishCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid RepublishConfigReq publication</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotNull @Valid ListConfigRevisionsQuery query</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo</code> |

### Resolve

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=24) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |
| <code>@NotNull OffsetDateTime asOf</code> |

## ai.neargo.shop.food.spi.DiningBatchQueryInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningBatchQueryInternalApi.java)

### Listing

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@Valid @NotNull ListDiningBatchesQuery query</code> |

## ai.neargo.shop.food.spi.DiningChannelSourceFactsPort

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningChannelSourceFactsPort.java)

### CurrentEvidence

| 字段声明（含校验） |
| --- |
| <code>Key key</code> |
| <code>DiningChannelSourceFacts.Current current</code> |
| <code>Instant observedAt</code> |
| <code>Instant validUntil</code> |
| <code>String sourceReceiptRef</code> |

## ai.neargo.shop.food.spi.DiningCheckoutInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningCheckoutInternalApi.java)

### Create

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String batchNo</code> |
| <code>CreateDiningSettlementReq request</code> |
| <code>String key</code> |

### Reference

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String settlementNo</code> |

### Pay

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String settlementNo</code> |
| <code>PayDiningSettlementReq request</code> |
| <code>String key</code> |

### Cancel

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String settlementNo</code> |
| <code>DiningSettlementVersionReq request</code> |

### StaffGet

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String settlementNo</code> |

### Original

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String number</code> |

## ai.neargo.shop.food.spi.DiningDeliveryInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningDeliveryInternalApi.java)

### StaffSave

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String cartNo</code> |
| <code>SaveReq definition</code> |
| <code>String key</code> |

### CustomerSave

| 字段声明（含校验） |
| --- |
| <code>String cartNo</code> |
| <code>CartAccess access</code> |
| <code>SaveReq definition</code> |
| <code>String key</code> |

### Source

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String cartNo</code> |

### CustomerQuery

| 字段声明（含校验） |
| --- |
| <code>String cartNo</code> |
| <code>CartAccess access</code> |

## ai.neargo.shop.food.spi.DiningIdentityInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningIdentityInternalApi.java)

### DeviceQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |

### Register

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>RegisterDeviceReq definition</code> |
| <code>String key</code> |

### DeviceRevoke

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String deviceNo</code> |
| <code>VersionReq definition</code> |
| <code>String key</code> |

### DeviceSession

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>String credential</code> |
| <code>DeviceSessionReq definition</code> |
| <code>String key</code> |

### StaffParty

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>StaffPartyReq definition</code> |
| <code>String key</code> |

### PartyRevoke

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String sessionNo</code> |
| <code>VersionReq definition</code> |
| <code>String key</code> |

### PartyQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String sessionNo</code> |

### SessionQuery

| 字段声明（含校验） |
| --- |
| <code>String sessionNo</code> |

### DeviceAccess

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>CartAccess access</code> |

## ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderAuthorityInternalApi.java)

### PlanCommand

| 字段声明（含校验） |
| --- |
| <code>Command command</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |

## ai.neargo.shop.food.spi.DiningOrderChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderChanged.java)

### DiningOrderChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String checkNo</code> |
| <code>String batchNo</code> |
| <code>String changeNo</code> |
| <code>String action</code> |
| <code>long sourceVersion</code> |
| <code>List<String> foodLineNos</code> |

## ai.neargo.shop.food.spi.DiningOrderInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningOrderInternalApi.java)

### CancellationSource

| 字段声明（含校验） |
| --- |
| <code>Command command</code> |
| <code>String key</code> |
| <code>String fulfillment</code> |

### StaffCancel

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String batchNo</code> |
| <code>CancelDiningOrderReq request</code> |
| <code>String key</code> |

### StaffPlace

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>CreateStaffDiningOrderReq request</code> |
| <code>String key</code> |

### Place

| 字段声明（含校验） |
| --- |
| <code>String store</code> |
| <code>CartAccess access</code> |
| <code>CreateDiningOrderReq request</code> |
| <code>String key</code> |

### CustomerGet

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String batchNo</code> |

### StaffGet

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String batchNo</code> |

### Original

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String number</code> |

## ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningPickupVerificationInternalApi.java)

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull VerifyPickupReq definition</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String idempotencyKey</code> |

## ai.neargo.shop.food.spi.DiningPricingInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningPricingInternalApi.java)

### StaffQuote

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>DiningQuoteReq request</code> |
| <code>String key</code> |

### StaffGet

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String cart</code> |
| <code>long version</code> |
| <code>String quote</code> |

### StaffConfirm

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String quote</code> |
| <code>DiningQuoteConfirmReq request</code> |

### CustomerQuote

| 字段声明（含校验） |
| --- |
| <code>String store</code> |
| <code>CartAccess access</code> |
| <code>DiningQuoteReq request</code> |
| <code>String key</code> |

### CustomerGet

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String cart</code> |
| <code>long version</code> |
| <code>String quote</code> |

### CustomerConfirm

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String quote</code> |
| <code>DiningQuoteConfirmReq request</code> |

## ai.neargo.shop.food.spi.DiningReportInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningReportInternalApi.java)

### RealtimeQuery

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>int version</code> |
| <code>boolean inventory</code> |

### ExportQuery

| 字段声明（含校验） |
| --- |
| <code>Query query</code> |
| <code>String key</code> |

### ExportReference

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String actor</code> |
| <code>String exportNo</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>List<String> stores</code> |
| <code>String actor</code> |
| <code>View view</code> |
| <code>LocalDate from</code> |
| <code>LocalDate to</code> |
| <code>Integer version</code> |
| <code>Set<String> metricCodes</code> |
| <code>int limit</code> |
| <code>MeasureFilter measure</code> |

### DayQuery

| 字段声明（含校验） |
| --- |
| <code>Scope scope</code> |
| <code>LocalDate date</code> |

## ai.neargo.shop.food.spi.DiningReturnInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningReturnInternalApi.java)

### CustomerApply

| 字段声明（含校验） |
| --- |
| <code>String store</code> |
| <code>CartAccess access</code> |
| <code>DiningReturnApplyReq request</code> |
| <code>String key</code> |

### CustomerReference

| 字段声明（含校验） |
| --- |
| <code>CartAccess access</code> |
| <code>String returnNo</code> |

### StaffReference

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String returnNo</code> |

### Original

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String number</code> |

## ai.neargo.shop.food.spi.DiningSessionInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningSessionInternalApi.java)

### RotateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull RotateTableTokenReq request</code> |

### RevokeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull RevokeTableTokenReq request</code> |

### ResolveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=128) String tableToken</code> |

### StartCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Size(max=32) String buyerNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull StartDiningSessionReq request</code> |

### EndCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String sessionNo</code> |
| <code>@NotBlank @Size(max=128) String accessToken</code> |
| <code>@Size(max=32) String buyerNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |

## ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DiningTransactionRecoveryInternalApi.java)

### Scan

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int limit</code> |

## ai.neargo.shop.food.spi.DishInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/DishInternalApi.java)

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListDishesQuery query</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String goodsNo</code> |

### SaveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String goodsNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SaveDishReq dish</code> |

## ai.neargo.shop.food.spi.FoodKdsInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodKdsInternalApi.java)

### Grant

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>GrantReq definition</code> |
| <code>String key</code> |

### Revoke

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String subscriptionNo</code> |
| <code>RevokeReq definition</code> |
| <code>String key</code> |

### Replay

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String subscriptionNo</code> |
| <code>long after</code> |
| <code>int limit</code> |

## ai.neargo.shop.food.spi.FoodMaintenanceInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodMaintenanceInternalApi.java)

### Run

| 字段声明（含校验） |
| --- |
| <code>Task task</code> |
| <code>@JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int limit</code> |

## ai.neargo.shop.food.spi.FoodPrintInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodPrintInternalApi.java)

### Command

| 字段声明（含校验） |
| --- |
| <code>Scope scope</code> |
| <code>PrintDocumentReq definition</code> |
| <code>String requestKey</code> |

## ai.neargo.shop.food.spi.FoodReturnStockInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodReturnStockInternalApi.java)

### Command

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String returnNo</code> |
| <code>String actorNo</code> |
| <code>Apply input</code> |
| <code>String key</code> |

### Query

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String returnNo</code> |
| <code>int page</code> |
| <code>int size</code> |

### Retry

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String returnNo</code> |
| <code>String operationNo</code> |
| <code>String actorNo</code> |

## ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodStationRuntimeInternalApi.java)

### Scope

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String stationNo</code> |

### Control

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String stationNo</code> |
| <code>ControlReq definition</code> |
| <code>String key</code> |

### Poll

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String subscriptionNo</code> |

### Receipt

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>String subscriptionNo</code> |
| <code>VoiceReceipt definition</code> |

## ai.neargo.shop.food.spi.FoodStockManagementInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodStockManagementInternalApi.java)

### ItemCreate

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>CreateItem input</code> |
| <code>String key</code> |

### ItemUpdate

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String itemNo</code> |
| <code>String actorNo</code> |
| <code>UpdateItem input</code> |
| <code>String key</code> |

### ItemQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String itemNo</code> |

### ItemsQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>int page</code> |
| <code>int size</code> |
| <code>String keyword</code> |
| <code>String kind</code> |
| <code>String status</code> |

### InitializeCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>Initialize input</code> |
| <code>String key</code> |

### PolicyCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String itemNo</code> |
| <code>String actorNo</code> |
| <code>Policy input</code> |
| <code>String key</code> |

### AdjustCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String itemNo</code> |
| <code>String actorNo</code> |
| <code>Adjust input</code> |
| <code>String key</code> |

### PageQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>int page</code> |
| <code>int size</code> |

### MovementsQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>int page</code> |
| <code>int size</code> |
| <code>String itemNo</code> |
| <code>String action</code> |
| <code>LocalDateTime from</code> |
| <code>LocalDateTime to</code> |

### StoreQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |

### ConfigWrite

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>ConfigCommand input</code> |
| <code>String key</code> |

### MappingWrite

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String itemNo</code> |
| <code>String actorNo</code> |
| <code>MappingCommand input</code> |
| <code>String key</code> |

### DeliveryQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>int page</code> |
| <code>int size</code> |
| <code>String status</code> |
| <code>String itemNo</code> |

### RetryCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String syncNo</code> |
| <code>String actorNo</code> |
| <code>String key</code> |

### ReconcileCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String actorNo</code> |
| <code>List<String> itemNos</code> |
| <code>String key</code> |

### ReconcileQuery

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String number</code> |
| <code>int page</code> |
| <code>int size</code> |

## ai.neargo.shop.food.spi.FoodStockSyncInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodStockSyncInternalApi.java)

### Dispatch

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>OutboxEnvelope envelope</code> |

## ai.neargo.shop.food.spi.FoodSupportInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/FoodSupportInternalApi.java)

### ReadinessQuery

| 字段声明（含校验） |
| --- |


### CasesQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>String status</code> |

### SourceQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@Valid @NotNull Source source</code> |

## ai.neargo.shop.food.spi.KitchenInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/KitchenInternalApi.java)

### PackingCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull PackingReq definition</code> |

### ReceiptQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String batchNo</code> |

### HandoverCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull HandoverReq definition</code> |

### FreezeQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotNull List<@NotBlank String> skus</code> |

### DispatchCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String batchNo</code> |
| <code>@NotBlank String dispatchKey</code> |

### QueueQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>String stationNo</code> |
| <code>String batchNo</code> |

### TaskQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String taskNo</code> |

### CourseQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String checkNo</code> |

### TaskCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String taskNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull TaskActionReq definition</code> |

### RemakeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String taskNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull RemakeReq definition</code> |

### CreateCourseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String checkNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull CourseReq definition</code> |

### CourseCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String courseNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@Valid @NotNull CourseActionReq definition</code> |

## ai.neargo.shop.food.spi.KitchenReturnInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/KitchenReturnInternalApi.java)

### Query

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String returnNo</code> |

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entityNo</code> |
| <code>@NotBlank String storeNo</code> |
| <code>@NotBlank String actorNo</code> |
| <code>@NotBlank String returnNo</code> |
| <code>@NotBlank String idempotencyKey</code> |
| <code>@NotNull @Valid HandleReq definition</code> |

## ai.neargo.shop.food.spi.MenuChannelBindingInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuChannelBindingInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateMenuChannelBindingReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid UpdateMenuChannelBindingReq definition</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String bindingNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String provider</code> |
| <code>@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |

## ai.neargo.shop.food.spi.MenuInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/MenuInternalApi.java)

### PageReceipt

| 字段声明（含校验） |
| --- |
| <code>List<MenuVO> records</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size</code> |

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateMenuReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid UpdateMenuReq definition</code> |

### ScheduleCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceMenuScheduleReq definition</code> |

### CompositionCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceMenuCompositionReq definition</code> |

### ContextsCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceMenuContextsReq definition</code> |

### DisableCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid DisableMenuReq definition</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@Valid ListMenusQuery query</code> |

## ai.neargo.shop.food.spi.ModifierInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ModifierInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateModifierReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String modifierNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid UpdateModifierReq definition</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListModifiersQuery query</code> |

## ai.neargo.shop.food.spi.OfflineDraftInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/OfflineDraftInternalApi.java)

### ChallengeCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>ChallengeRequest request</code> |

### EnrollCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>Enroll request</code> |

### LeaseCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>LeaseRequest request</code> |

### CaptureCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>Upload request</code> |

### ReplayCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>Replay request</code> |

### StateCommand

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>ai.neargo.shop.food.dto.CartAccess access</code> |

## ai.neargo.shop.food.spi.OptionGroupInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/OptionGroupInternalApi.java)

### ReplaceCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String groupNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceOptionGroupItemsReq definition</code> |

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull CreateOptionGroupReq group</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String configNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListOptionGroupsQuery query</code> |

## ai.neargo.shop.food.spi.PricingSourceInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/PricingSourceInternalApi.java)

### StaffCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String entityNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String storeNo</code> |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long expectedVersion</code> |

### CustomerCommand

| 字段声明（含校验） |
| --- |
| <code>@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) String cartNo</code> |
| <code>@Valid @NotNull CartAccess access</code> |
| <code>@JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long expectedVersion</code> |

## ai.neargo.shop.food.spi.ProductionConsumptionInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ProductionConsumptionInternalApi.java)

### PendingCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String entity</code> |
| <code>@NotBlank @Size(max = 32) String store</code> |
| <code>@NotBlank @Size(max = 32) String actor</code> |
| <code>@Valid @NotNull ProductionContracts.PendingQuery query</code> |

### AdjustmentCommand

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String taskNo</code> |
| <code>ProductionContracts.AdjustmentReq request</code> |
| <code>String key</code> |

### ReverseCommand

| 字段声明（含校验） |
| --- |
| <code>String entity</code> |
| <code>String store</code> |
| <code>String actor</code> |
| <code>String sourceEventNo</code> |
| <code>ProductionContracts.ReverseReq request</code> |
| <code>String key</code> |

### ListCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String entity</code> |
| <code>@NotBlank @Size(max = 32) String store</code> |
| <code>@NotBlank @Size(max = 32) String actor</code> |
| <code>@Valid @NotNull ListQuery query</code> |

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String entity</code> |
| <code>@NotBlank @Size(max = 32) String store</code> |
| <code>@NotBlank @Size(max = 32) String actor</code> |
| <code>@NotBlank @Size(max = 32) String sourceEventNo</code> |

## ai.neargo.shop.food.spi.QueueInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/QueueInternalApi.java)

### IssueCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid IssueReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ListQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull @Valid Query query</code> |

### Identity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String ticketNo</code> |

### CallNextCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotNull LocalDate businessDate</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ChangeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String ticketNo</code> |
| <code>@NotBlank @Size(max=16) String action</code> |
| <code>@NotNull @Valid ChangeReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### SeatCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entity</code> |
| <code>@NotBlank @Size(max=32) String store</code> |
| <code>@NotBlank @Size(max=32) String actor</code> |
| <code>@NotBlank @Size(max=32) String ticketNo</code> |
| <code>@NotNull @Valid SeatQueueReq request</code> |

## ai.neargo.shop.food.spi.QueueNotificationPort

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/QueueNotificationPort.java)

### Request

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String ticketNo</code> |
| <code>String contactRef</code> |
| <code>String originalKey</code> |

### Receipt

| 字段声明（含校验） |
| --- |
| <code>String originalKey</code> |
| <code>String status</code> |
| <code>String providerRef</code> |

## ai.neargo.shop.food.spi.RecipeInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/RecipeInternalApi.java)

### PageReceipt

| 字段声明（含校验） |
| --- |
| <code>List<RecipeVO> records</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size</code> |

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateRecipeReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceRecipeDefinitionReq definition</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@Valid ListRecipesQuery query</code> |

## ai.neargo.shop.food.spi.ReservationInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ReservationInternalApi.java)

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid @NotNull ListReservationsQuery query</code> |

### NumberLookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String reservationNo</code> |

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@Valid @NotNull CreateReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ChangeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=32) String reservationNo</code> |
| <code>@Valid @NotNull ChangeReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### TransitionCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=32) String reservationNo</code> |
| <code>@Valid @NotNull TransitionReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

### ArriveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=32) String reservationNo</code> |
| <code>@Valid @NotNull ArriveReq request</code> |
| <code>@NotBlank @Size(max=128) String key</code> |

## ai.neargo.shop.food.spi.SalesRestrictionInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/SalesRestrictionInternalApi.java)

### PageReceipt

| 字段声明（含校验） |
| --- |
| <code>List<SalesRestrictionVO> records</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long total</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long page</code> |
| <code>@JsonDeserialize(using=CatalogRevisionDeserializer.class) Long size</code> |

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid SalesRestrictionReq definition</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@Valid SalesRestrictionQuery query</code> |

### Evaluation

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment</code> |

## ai.neargo.shop.food.spi.ServiceCalendarInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ServiceCalendarInternalApi.java)

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotNull LocalDate localDate</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@NotNull @Valid SaveCalendarReq calendar</code> |

## ai.neargo.shop.food.spi.ServiceEntitlementInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/ServiceEntitlementInternalApi.java)

### GrantCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String formNo</code> |
| <code>@Valid @NotNull Grant request</code> |
| <code>@NotBlank String key</code> |

### ConsumeCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String entitlementNo</code> |
| <code>@Valid @NotNull Consume request</code> |
| <code>@NotBlank String key</code> |

### ListCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String formNo</code> |

### ExtensionCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String formNo</code> |
| <code>@Valid @NotNull Extension request</code> |
| <code>@NotBlank String key</code> |

### PlanCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String formNo</code> |
| <code>@Valid @NotNull Plan request</code> |
| <code>@NotBlank String key</code> |

### ConfirmCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank String entity</code> |
| <code>@NotBlank String store</code> |
| <code>@NotBlank String actor</code> |
| <code>@NotBlank String formNo</code> |
| <code>@Valid @NotNull ConfirmAttendance request</code> |
| <code>@NotBlank String key</code> |

## ai.neargo.shop.food.spi.StationInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/StationInternalApi.java)

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid CreateStationReq definition</code> |

### UpdateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid UpdateStationReq definition</code> |

### RoutesCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String actorNo</code> |
| <code>@NotBlank @Size(max=128) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String idempotencyKey</code> |
| <code>@NotNull @Valid ReplaceStationRoutesReq definition</code> |

### GetQuery

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo</code> |

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo</code> |
| <code>@NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page</code> |
| <code>@JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size</code> |

## ai.neargo.shop.food.spi.StoreOperationInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/StoreOperationInternalApi.java)

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String entityNo</code> |
| <code>@NotBlank @Size(max = 32) String storeNo</code> |
| <code>Instant at</code> |

## ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/StorePolicyPublicationInternalApi.java)

### Command

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max = 32) String entityNo</code> |
| <code>@NotBlank @Size(max = 32) String storeNo</code> |
| <code>@NotBlank @Size(max = 32) String actorNo</code> |
| <code>@NotBlank @Size(max = 128) String idempotencyKey</code> |
| <code>@NotNull @Valid CreatePolicyReq policy</code> |

## ai.neargo.shop.food.spi.TableInternalApi

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/spi/TableInternalApi.java)

### Lookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListTableAreasQuery query</code> |

### CreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull CreateTableAreaReq area</code> |

### SaveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String areaNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SaveTableAreaReq area</code> |

### StatusCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String areaNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SetStatusTableAreaReq area</code> |

### TableLookup

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@Valid ListTablesQuery query</code> |

### TableIdentity

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |

### TableCreateCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull CreateTableReq table</code> |

### TableSaveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SaveTableReq table</code> |

### TableStatusCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull SetStatusTableReq table</code> |

### TableArchiveCommand

| 字段声明（含校验） |
| --- |
| <code>@NotBlank @Size(max=32) String entityNo</code> |
| <code>@NotBlank @Size(max=32) String storeNo</code> |
| <code>@NotBlank @Size(max=32) String tableNo</code> |
| <code>@NotBlank @Size(max=32) String actorNo</code> |
| <code>@NotBlank @Size(max=128) String idempotencyKey</code> |
| <code>@Valid @NotNull ArchiveTableReq table</code> |

## ai.neargo.shop.spi.food.BundleChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/BundleChanged.java)

### BundleChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String skuNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>BundleConfigVO after</code> |

## ai.neargo.shop.spi.food.CartChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/CartChanged.java)

### CartChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String cartNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |

## ai.neargo.shop.spi.food.CheckChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/CheckChanged.java)

### CheckChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String checkNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>CheckVO before</code> |
| <code>CheckVO after</code> |
| <code>String reason</code> |
| <code>List<SeatReq> beforeSeats</code> |
| <code>List<SeatReq> afterSeats</code> |

## ai.neargo.shop.spi.food.ConfigPublished

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ConfigPublished.java)

### ConfigPublished

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String operationNo</code> |
| <code>ConfigRevisionVO publication</code> |

## ai.neargo.shop.spi.food.DiningDeviceChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningDeviceChanged.java)

### DiningDeviceChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String deviceNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>long generation</code> |
| <code>String operationNo</code> |

## ai.neargo.shop.spi.food.DiningOrderAccepted

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningOrderAccepted.java)

### DiningOrderAccepted

| 字段声明（含校验） |
| --- |
| <code>String batchNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String checkNo</code> |
| <code>String orderNo</code> |
| <code>String subOrderNo</code> |
| <code>LocalDate businessDate</code> |
| <code>String fulfillment</code> |
| <code>Instant acceptedAt</code> |

## ai.neargo.shop.spi.food.DiningQueueChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningQueueChanged.java)

### DiningQueueChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String ticketNo</code> |
| <code>LocalDate businessDate</code> |
| <code>String status</code> |
| <code>int partySize</code> |
| <code>String checkNo</code> |
| <code>String actorNo</code> |
| <code>long version</code> |

## ai.neargo.shop.spi.food.DiningServiceEvent

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningServiceEvent.java)

### DiningServiceEvent

| 字段声明（含校验） |
| --- |
| <code>String eventNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String batchNo</code> |
| <code>String ticketNo</code> |
| <code>String taskNo</code> |
| <code>String foodLineNo</code> |
| <code>String componentNo</code> |
| <code>String stationNo</code> |
| <code>String taskRole</code> |
| <code>long routeVersion</code> |
| <code>String action</code> |
| <code>BigDecimal qty</code> |
| <code>String reason</code> |
| <code>String actorNo</code> |
| <code>Instant occurredAt</code> |

## ai.neargo.shop.spi.food.DiningSessionChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningSessionChanged.java)

### DiningSessionChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String tableNo</code> |
| <code>String sessionNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |

## ai.neargo.shop.spi.food.DiningStationRuntimeChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DiningStationRuntimeChanged.java)

### DiningStationRuntimeChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String stationNo</code> |
| <code>String commandNo</code> |
| <code>String action</code> |
| <code>long sourceVersion</code> |
| <code>String status</code> |
| <code>String reason</code> |
| <code>Instant occurredAt</code> |

## ai.neargo.shop.spi.food.DishChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/DishChanged.java)

### DishChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String goodsNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>DishVO before</code> |
| <code>DishVO after</code> |

## ai.neargo.shop.spi.food.FoodStockSyncRequested

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/FoodStockSyncRequested.java)

### FoodStockSyncRequested

| 字段声明（含校验） |
| --- |
| <code>String syncNo</code> |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>long generation</code> |
| <code>String action</code> |
| <code>String payloadHash</code> |
| <code>Snapshot snapshot</code> |

## ai.neargo.shop.spi.food.MenuChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChanged.java)

### MenuChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String menuNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>MenuVO after</code> |
| <code>MenuScheduleVO schedule</code> |
| <code>MenuCompositionVO composition</code> |
| <code>List<MenuContextInput> contexts</code> |

## ai.neargo.shop.spi.food.MenuChannelBindingChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/MenuChannelBindingChanged.java)

### MenuChannelBindingChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String bindingNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>MenuChannelBindingVO after</code> |

## ai.neargo.shop.spi.food.ModifierChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ModifierChanged.java)

### ModifierChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String modifierNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>ModifierConfigVO after</code> |

## ai.neargo.shop.spi.food.OptionGroupChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/OptionGroupChanged.java)

### OptionGroupChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String groupNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>OptionGroupConfigVO after</code> |

## ai.neargo.shop.spi.food.RecipeChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/RecipeChanged.java)

### RecipeChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String recipeNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>RecipeVO after</code> |

## ai.neargo.shop.spi.food.ReservationChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ReservationChanged.java)

### ReservationChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String reservationNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>DetailVO after</code> |
| <code>String reason</code> |

## ai.neargo.shop.spi.food.SalesRestrictionChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/SalesRestrictionChanged.java)

### SalesRestrictionChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String restrictionNo</code> |
| <code>String action</code> |
| <code>String operationNo</code> |
| <code>SalesRestrictionVO after</code> |

## ai.neargo.shop.spi.food.ServiceCalendarSaved

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/ServiceCalendarSaved.java)

### ServiceCalendarSaved

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String calendarNo</code> |
| <code>LocalDate localDate</code> |
| <code>String mode</code> |
| <code>long version</code> |
| <code>String operationNo</code> |

## ai.neargo.shop.spi.food.StationChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/StationChanged.java)

### StationChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String configNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>Object after</code> |

## ai.neargo.shop.spi.food.StorePolicyPublished

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/StorePolicyPublished.java)

### StorePolicyPublished

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String policyNo</code> |
| <code>long version</code> |
| <code>Instant effectiveFrom</code> |
| <code>String operationNo</code> |

## ai.neargo.shop.spi.food.TableAreaChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/TableAreaChanged.java)

### TableAreaChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String areaNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>TableAreaVO before</code> |
| <code>TableAreaVO after</code> |
| <code>String reason</code> |

## ai.neargo.shop.spi.food.TableChanged

[源码](../../../backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/spi/food/TableChanged.java)

### TableChanged

| 字段声明（含校验） |
| --- |
| <code>String entityNo</code> |
| <code>String storeNo</code> |
| <code>String tableNo</code> |
| <code>String action</code> |
| <code>long version</code> |
| <code>String operationNo</code> |
| <code>TableVO before</code> |
| <code>TableVO after</code> |
| <code>String reason</code> |

