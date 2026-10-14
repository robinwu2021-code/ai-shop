"use client";

// 建平台自营商家 —— 一个刻意绕开进件与审核的入口。
//
// ──────────────────────────────────────────────────────
// 为什么会有这个页面
// ──────────────────────────────────────────────────────
// 第三方商家走「C 端交营业执照 → 运营审核 → 通过后建主体」。
// 平台自己走那条路，就是**向自己提交执照、再由自己审自己**。
// 这与售后链路上曾经那处「自营单派给商家、而那个商家就是平台」是同一个形状。
//
// 进件资料的全部意义是「核验那个第三方是谁、有没有资格经营」。
// 自营场景下不存在第三方主体，这个问题**不成立**，不是被豁免了。
//
// ──────────────────────────────────────────────────────
// 唯一必填的那一项，和它为什么必填
// ──────────────────────────────────────────────────────
// 覆盖社区。没有覆盖社区的商家**上着架却对谁都不可见**，
// 而这个故障没有任何报错：商家和运营都只看到「一个订单都不来」（ADR-009）。
// 所以这里在提交按钮上硬拦，而不是让后端 400 之后再来解释。
import { Fragment, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { SelfOperatedResult, SelfOperatedStore } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { FilterSelect } from "@/components/ui/filter-select";
import { Textarea } from "@/components/ui/textarea";
import type { MerchantsCopy } from "./copy";

const PHONE = /^1[3-9]\d{9}$/;

export function SelfOperatedTab({ c, canCreate }: { c: MerchantsCopy; canCreate: boolean }) {
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [industry, setIndustry] = useState("");
  const [scope, setScope] = useState("COMMUNITY");
  const [communityNos, setCommunityNos] = useState<string[]>([]);
  const [done, setDone] = useState<SelfOperatedResult | null>(null);
  const [storeName, setStoreName] = useState("");
  const [storeAddress, setStoreAddress] = useState("");
  const [addedStores, setAddedStores] = useState<SelfOperatedStore[]>([]);

  /*
   * 行业不是类目。类目回答「这家店卖什么」（水果 → FRESH_FRUIT），
   * 行业回答「它属于哪门生意」（线下零售）—— 后者决定能不能以小微主体进件、
   * 也是 points_forced 的来源。两个都要，且都不该留空。
   */
  const industries = useQuery({
    queryKey: ["industries", "for-self-operated"],
    queryFn: () => api.listIndustries(),
  });

  const communities = useQuery({
    queryKey: ["communities", "for-self-operated"],
    queryFn: () => api.listCommunities({ page: 1, size: 200 }),
  });

  const create = useMutation({
    mutationFn: () => api.createSelfOperated({
      phone: phone.trim(), name: name.trim(), serviceScope: scope,
      industry: industry || undefined,
      communityNos: scope === "COMMUNITY" ? communityNos : [],
      description: description.trim() || undefined,
    }),
    onSuccess: (r) => {
      setDone(r);
      // 幂等命中与新建要说成两件事：都提示「建好了」的话，
      // 运营连点两次会以为自己建出了两家店，而库里只有一家
      notify.success(r.created ? c.soToastCreated : c.soToastIdempotent);
    },
  });

  const addStore = useMutation({
    mutationFn: () => api.addSelfOperatedStore({
      merchantNo: done!.merchantNo,
      name: storeName.trim(),
      address: storeAddress.trim() || undefined,
    }),
    onSuccess: (st) => {
      setAddedStores((prev) => [...prev, st]);
      setStoreName("");
      setStoreAddress("");
      notify.success(c.soStoreAdded);
    },
  });

  const phoneOk = PHONE.test(phone.trim());
  const byCommunity = scope === "COMMUNITY";
  const ready = phoneOk && name.trim().length > 0 && (!byCommunity || communityNos.length > 0);

  const toggle = (no: string) =>
    setCommunityNos((prev) => prev.includes(no) ? prev.filter((x) => x !== no) : [...prev, no]);

  return (
    <div className="space-y-4">
      {/* 不可关闭：这段说明是这个入口的适用边界，每次打开都要在 */}
      <Notice tone="warning">{c.soWarning}</Notice>

      <ConfigCard title={c.soTitle} notice={c.soDesc}>
        <div className="max-w-xl space-y-4">
          <div>
            <Label>{c.soPhone}</Label>
            <Input
              className="mt-1"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder={c.soPhonePlaceholder}
              disabled={!canCreate}
            />
            {/* 号码没填完就报错会一路红着，只在填够 11 位之后判 */}
            {phone.trim().length >= 11 && !phoneOk && (
              <Notice tone="danger" className="mt-2">{c.soPhoneBad}</Notice>
            )}
            <p className="mt-1 txt-caption text-muted-foreground">{c.soPhoneHint}</p>
          </div>

          <div>
            <Label>{c.soName}</Label>
            <Input
              className="mt-1"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={c.soNamePlaceholder}
              disabled={!canCreate}
            />
            <p className="mt-1 txt-caption text-muted-foreground">{c.soNameHint}</p>
          </div>

          <div>
            <Label>{c.soDescription}</Label>
            <Textarea
              className="mt-1"
              value={description}
              onChange={setDescription}
              rows={2}
              disabled={!canCreate}
            />
          </div>

          <div>
            <Label>{c.soIndustry}</Label>
            <FilterSelect
              className="mt-1"
              value={industry}
              onChange={setIndustry}
              options={(industries.data ?? [])
                .filter((i) => i.enabled)
                .map((i) => ({ value: i.industry, label: i.name }))}
            />
            <p className="mt-1 txt-caption text-muted-foreground">{c.soIndustryHint}</p>
          </div>

          <div>
            <Label>{c.soScope}</Label>
            <FilterSelect
              className="mt-1"
              value={scope}
              onChange={setScope}
              options={[
                { value: "COMMUNITY", label: c.soScopeCommunity },
                { value: "CITY", label: c.soScopeCity },
              ]}
            />
            <p className="mt-1 txt-caption text-muted-foreground">{c.soScopeHint}</p>
          </div>

          {byCommunity && (
            <div>
              <Label>{c.soCommunities}</Label>
              <div className="mt-2 flex flex-wrap gap-3">
                {(communities.data?.records ?? []).map((cm) => (
                  <CheckboxField
                    key={cm.communityNo}
                    checked={communityNos.includes(cm.communityNo)}
                    onChange={() => toggle(cm.communityNo)}
                    label={cm.name}
                    disabled={!canCreate}
                  />
                ))}
              </div>
              {!communities.isLoading && (communities.data?.records ?? []).length === 0 && (
                // 社区目录空着时这个表单一定提交不了 —— 要直说缺什么，
                // 否则运营只会看到一个永远点不动的按钮
                <Notice tone="danger" className="mt-2">{c.soNoCommunities}</Notice>
              )}
              {communityNos.length === 0 && (communities.data?.records ?? []).length > 0 && (
                <Notice tone="warning" className="mt-2">{c.soNeedCommunity}</Notice>
              )}
            </div>
          )}

          {!byCommunity && !communities.isLoading
            && (communities.data?.records ?? []).length === 0 && (
            /*
              全市档不要求勾社区 —— **但那不等于就可见了**。可见性一律展开成小区号，
              库里一个小区都没有时全市档同样是 0。这句话要在提交之前说，
              而不是等建完看着 reachableCommunities=0 再解释。
            */
            <Notice tone="warning">{c.soCityNoCommunities}</Notice>
          )}

          <Button
            onClick={() => create.mutate()}
            disabled={!canCreate || !ready || create.isPending}
          >
            {create.isPending ? c.soSubmitting : c.soSubmit}
          </Button>
        </div>
      </ConfigCard>

      {/*
        开店这张卡只在**已经有主体**之后出现。
        平台自己的店没有「商家」去点 B 端那个建店按钮 —— 与建主体是同一个形状的缺口。
        入口挂在这里而不是门店档案页：拿到主体号的那一刻正是要开店的那一刻，
        让人再去另一个页面按主体号搜一遍是白绕。
      */}
      {done && (
        <ConfigCard title={c.soStoreTitle} notice={c.soStoreDesc}>
          <div className="max-w-xl space-y-4">
            <div>
              <Label>{c.soStoreName}</Label>
              <Input
                className="mt-1"
                value={storeName}
                onChange={(e) => setStoreName(e.target.value)}
                placeholder={c.soStoreNamePlaceholder}
                disabled={!canCreate}
              />
            </div>
            <div>
              <Label>{c.soStoreAddress}</Label>
              <Input
                className="mt-1"
                value={storeAddress}
                onChange={(e) => setStoreAddress(e.target.value)}
                disabled={!canCreate}
              />
            </div>
            <Button
              onClick={() => addStore.mutate()}
              disabled={!canCreate || !storeName.trim() || addStore.isPending}
            >
              {addStore.isPending ? c.soStoreAdding : c.soStoreSubmit}
            </Button>

            {addedStores.length > 0 && (
              <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 txt-body">
                {addedStores.map((st) => (
                  <Fragment key={st.storeNo}>
                    <dt className="text-muted-foreground tabular-nums">{st.storeNo}</dt>
                    <dd className="font-medium">
                      {st.name}
                      <span className="ml-2 txt-caption text-muted-foreground">{st.businessMode}</span>
                    </dd>
                  </Fragment>
                ))}
              </dl>
            )}
          </div>
        </ConfigCard>
      )}

      {done && (
        <ConfigCard title={c.soResultTitle}>
          <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 txt-body">
            <dt className="text-muted-foreground">{c.soResultMerchant}</dt>
            <dd className="font-medium tabular-nums">{done.merchantNo}</dd>
            <dt className="text-muted-foreground">{c.soResultStore}</dt>
            <dd className="font-medium tabular-nums">{done.storeNo}</dd>
            <dt className="text-muted-foreground">{c.soResultOwner}</dt>
            <dd className="font-medium tabular-nums">{done.ownerUserNo}</dd>
            {/*
              这两行是**回读值**，不是把刚才填的东西显示一遍。
              「自营」由它们共同成立：主体级钱先进平台账户，门店级平台是销售主体。
              少任何一个都会得到一家看起来是自营、而售后派给商家自己的店。
            */}
            <dt className="text-muted-foreground">{c.soResultFunds}</dt>
            <dd className="font-medium">{done.fundsMode}</dd>
            <dt className="text-muted-foreground">{c.soResultBusiness}</dt>
            <dd className="font-medium">{done.businessMode}</dd>
            <dt className="text-muted-foreground">{c.soResultScope}</dt>
            <dd className="font-medium">{done.serviceScope}</dd>
            <dt className="text-muted-foreground">{c.soResultSelfOp}</dt>
            <dd className="font-medium">{done.selfOperated ? c.soYes : c.soNo}</dd>
            <dt className="text-muted-foreground">{c.soResultReach}</dt>
            <dd className="font-medium tabular-nums">{done.reachableCommunities}</dd>
          </dl>

          {/*
            可达 0 要当成**失败态来显示**，虽然接口返回的是成功。
            这家店确实建出来了，但它现在对谁都不可见，而这个状态
            在任何别的界面上都看不出异常（商品能上架、店在列表里、零订单）。
          */}
          {done.reachableCommunities === 0 && (
            <Notice tone="danger" className="mt-3">{c.soResultReachZero}</Notice>
          )}
          <Notice tone={done.created ? "muted" : "warning"} className="mt-3">
            {done.created
              ? fill(c.soResultCreated, { no: done.merchantNo })
              : fill(c.soResultIdempotent, { no: done.merchantNo })}
          </Notice>
        </ConfigCard>
      )}
    </div>
  );
}
