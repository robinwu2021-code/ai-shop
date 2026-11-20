"use client";

// 单一任务的编辑面板（抽屉内容）。由 ActionHost 的 drawer 操作渲染：
//   render: (ctx) => <ActionForm fields={F} initial={...} draft={{ operator, page, action: "edit", id }} onSubmit={save} onDone={ctx.close} />
//
// - 表单用 FormDrawer 的 inDrawer 形态：字段原地渲染、按钮进**当前**抽屉底栏，不叠层。
// - 接 useDraft：改动自动存本地，关抽屉、切页面、刷新都不丢；提交成功清掉；敏感字段不进草稿。
// - 一个 ActionForm = 一件事。字段多到要分段时，先考虑拆成几个操作（TDD §2.2 规则 3）。
import * as React from "react";
import { FormDrawer, type FieldDef } from "./form-drawer";
import { DraftNotice } from "./draft-notice";
import { useDraft } from "@/lib/use-draft";
import type { DraftRef } from "@/lib/draft";
import { notify } from "@/lib/notify";
import { useI18n } from "@/lib/i18n";

export function ActionForm<V extends Record<string, unknown>>({
  fields, initial, draft, baseVersion, isEdit = false, onSubmit, onDone, successMessage, notify: toast = true,
}: {
  fields: FieldDef[];
  initial: V;
  /** 草稿定位；null = 不存草稿（操作人未知时）。 */
  draft: DraftRef | null;
  /** 服务端当前版本，用于识别「草稿基于旧版本」。 */
  baseVersion?: string | number | null;
  isEdit?: boolean;
  onSubmit: (values: V) => Promise<unknown>;
  /** 提交成功或取消后调用，通常是 ctx.close。 */
  onDone: () => void;
  successMessage?: string;
  /** 调用方已自行提示成功 / 失败（如支付中心的 useRun）时传 false，避免两条 toast */
  notify?: boolean;
}) {
  const { t } = useI18n();
  const sensitiveKeys = React.useMemo(() => fields.filter((f) => f.sensitive).map((f) => f.key), [fields]);
  const d = useDraft<V>({ ref: draft, initial, sensitiveKeys, baseVersion });
  const [submitting, setSubmitting] = React.useState(false);

  const submit = async () => {
    setSubmitting(true);
    try {
      await onSubmit(d.values);
      d.clear();
      if (toast) notify.success(successMessage ?? t("common.success"));
      onDone();
    } catch (e) {
      if (toast) notify.error(t("actionKit.submitFailed", { msg: e instanceof Error ? e.message : String(e) }));
    } finally {
      setSubmitting(false);
    }
  };

  const cancel = () => {
    // 草稿已在本地；告诉人可以稍后继续，而不是让他以为改动没了
    if (d.dirty && draft) { d.flush(); notify.info(t("actionKit.draftKept")); }
    onDone();
  };

  return (
    <>
      <DraftNotice restored={d.restored} conflict={d.conflict} persistFailed={d.persistFailed} onDiscard={d.discard} />
      <FormDrawer
        inDrawer
        open
        onOpenChange={(o) => { if (!o) cancel(); }}
        titleNew=""
        titleEdit=""
        isEdit={isEdit}
        fields={fields}
        value={d.values}
        onChange={(v) => d.setValues(v as V)}
        onSubmit={() => void submit()}
        submitting={submitting}
      />
    </>
  );
}
