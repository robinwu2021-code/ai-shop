import { useI18n } from "vue-i18n";
import { recipientName } from "@shared/utils/recipient";

export function useRecipientName() {
  const { t, locale } = useI18n();
  return (name?: string | null, salutation?: string | null) => recipientName(name, salutation, {
    mr: String(t("address.salutationMr")), ms: String(t("address.salutationMs")),
    fallback: String(t("address.salutationFallback")),
    prefix: !(locale?.value ?? "zh-CN").startsWith("zh"),
  });
}
