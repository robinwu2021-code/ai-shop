/** Points requests inherit the Ops shell locale; this adapter remains for the shared HTTP client. */
export function pointsAcceptLanguage(_path: string, inherited: string) {
  return inherited;
}
