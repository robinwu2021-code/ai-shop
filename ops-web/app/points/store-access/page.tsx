import { redirect } from "next/navigation";

/** Store-level enrollment is deferred; stores participate by default in the current release. */
export default function LegacyPointsStoreAccessPage() {
  redirect("/points/types");
}
