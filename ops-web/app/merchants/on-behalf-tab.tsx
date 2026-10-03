"use client";

// 代商家进件（三期）—— BD 在店里把执照拍下来、当场替老板填完。
//
// ──────────────────────────────────────────────────────
// 它与隔壁「建平台自营商家」不是同一件事
// ──────────────────────────────────────────────────────
// 两个入口长得几乎一模一样，而这正是要在这儿写清楚的原因。
//
// 自营能跳过进件，靠的是一条具体的论证：进件资料的全部意义是「核验那个第三方
// 是谁、有没有资格经营」，而自营场景下不存在第三方主体 —— 这个问题**不成立**，
// 不是被豁免了。**代填的时候第三方是存在的**，论证不成立，豁免也就不成立。
//
// 所以这一页：执照照要、订阅额度照吃、单子照进审核队列、审核通过才建主体。
// 本期只改「谁来填这张表」。
//
// ──────────────────────────────────────────────────────
// 提交之后不会有主体
// ──────────────────────────────────────────────────────
// 这是最容易被误解的一点，界面上因此说了两遍（顶部警示 + 成功回执）。
// 制单与审核分离在这个仓库里是有先例的，而代填最需要那一层：
// 资料是运营录的，核验不该也是同一个人 —— 后端按 submitted_by 拦自审（403）。
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { ApplyOnBehalfResult } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { FilterSelect } from "@/components/ui/filter-select";
import { Textarea } from "@/components/ui/textarea";
import type { MerchantsCopy } from "./copy";

const PHONE = /^1[3-9]\d{9}$/;

export function OnBehalfTab({ c, canSubmit }: { c: MerchantsCopy; canSubmit: boolean }) {
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [subject, setSubject] = useState("ENTERPRISE");
  const [licenseUrl, setLicenseUrl] = useState("");
  const [licenseCode, setLicenseCode] = useState("");
  const [contactName, setContactName] = useState("");
  const [contactPhone, setContactPhone] = useState("");
  const [category, setCategory] = useState("");
  const [industry, setIndustry] = useState("");
  const [description, setDescription] = useState("");
  const [done, setDone] = useState<ApplyOnBehalfResult | null>(null);

  const industries = useQuery({
    queryKey: ["industries", "for-on-behalf"],
    queryFn: () => api.listIndustries(),
  });

  const submit = useMutation({
    mutationFn: () => api.applyOnBehalf({
      phone: phone.trim(),
      name: name.trim(),
      subject,
      contactName: contactName.trim() || undefined,
      contactPhone: contactPhone.trim() || undefined,
      category: category.trim() || undefined,
      description: description.trim() || undefined,
      industry: industry || undefined,
      /*
       * **结构化资质，不是那个纯 URL 数组。** 后端的执照闸
       * （requireLicenseIfNeeded）只在 qualificationItems 非 null 时生效 ——
       * 只传 qualifications 的话闸门整条不跑，这一页就成了「代填可以不交执照」。
       */
      qualificationItems: [{
        type: "BUSINESS_LICENSE",
        code: licenseCode.trim() || undefined,
        imageUrl: licenseUrl.trim() || undefined,
      }],
    }),
    onSuccess: (r) => setDone(r),
    onError: (e: Error) => notify.error(e.message),
  });

  const phoneOk = PHONE.test(phone.trim());
  // 执照是这一页与自营那一页的分水岭，所以它进 ready 而不是靠后端回一个 400
  const ready = phoneOk && name.trim().length > 0 && licenseUrl.trim().length > 0;

  return (
    <div className="space-y-4">
      {/* 不可关闭：这段说明是这个入口的适用边界，每次打开都要在 */}
      <Notice tone="warning">{c.obWarning}</Notice>
      {!canSubmit && <Notice tone="danger">{c.obNoPerm}</Notice>}

      <ConfigCard title={c.obTitle} notice={c.obDesc}>
        <div className="max-w-xl space-y-4">
          <div>
            <Label>{c.obPhone}</Label>
            <Input
              className="mt-1"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder={c.soPhonePlaceholder}
              disabled={!canSubmit}
            />
            {/* 号码没填完就报错会一路红着，只在填够 11 位之后判 */}
            {phone.trim().length >= 11 && !phoneOk && (
              <Notice tone="danger" className="mt-2">{c.soPhoneBad}</Notice>
            )}
            <p className="mt-1 txt-caption text-muted-foreground">{c.obPhoneHint}</p>
          </div>

          <div>
            <Label>{c.obName}</Label>
            <Input
              className="mt-1"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={c.soNamePlaceholder}
              disabled={!canSubmit}
            />
          </div>

          <div>
            <Label>{c.obSubject}</Label>
            <FilterSelect
              className="mt-1"
              value={subject}
              onChange={setSubject}
              options={[
                { value: "ENTERPRISE", label: c.obSubjectEnterprise },
                { value: "INDIVIDUAL", label: c.obSubjectIndividual },
              ]}
            />
            <p className="mt-1 txt-caption text-muted-foreground">{c.obSubjectHint}</p>
          </div>

          <div>
            <Label>{c.obLicense}</Label>
            <Input
              className="mt-1"
              value={licenseUrl}
              onChange={(e) => setLicenseUrl(e.target.value)}
              disabled={!canSubmit}
            />
            <Label className="mt-2 block">{c.obLicenseCode}</Label>
            <Input
              className="mt-1"
              value={licenseCode}
              onChange={(e) => setLicenseCode(e.target.value)}
              disabled={!canSubmit}
            />
            <p className="mt-1 txt-caption text-muted-foreground">{c.obLicenseHint}</p>
          </div>

          <div>
            <Label>{c.obContactName}</Label>
            <Input
              className="mt-1"
              value={contactName}
              onChange={(e) => setContactName(e.target.value)}
              disabled={!canSubmit}
            />
          </div>

          <div>
            <Label>{c.obContactPhone}</Label>
            <Input
              className="mt-1"
              value={contactPhone}
              onChange={(e) => setContactPhone(e.target.value)}
              disabled={!canSubmit}
            />
          </div>

          <div>
            <Label>{c.obCategory}</Label>
            <Input
              className="mt-1"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              disabled={!canSubmit}
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
            <Label>{c.soDescription}</Label>
            <Textarea
              className="mt-1"
              value={description}
              onChange={setDescription}
              rows={2}
              disabled={!canSubmit}
            />
          </div>

          <Button
            onClick={() => submit.mutate()}
            disabled={!canSubmit || !ready || submit.isPending}
          >
            {c.obSubmit}
          </Button>

          {done && (
            /*
             * 回执刻意说「进队列」而不是「建好了」。
             * 代填最容易被读成「运营把这家店开出来了」—— 而它只是录了一张表。
             */
            <Notice tone="info">
              {c.obDone}：{done.applyNo}
              <p className="mt-1 txt-caption">{c.obDoneHint}</p>
            </Notice>
          )}
        </div>
      </ConfigCard>
    </div>
  );
}
