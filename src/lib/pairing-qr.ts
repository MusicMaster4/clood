export async function pairingQrDataUrl(uri: string, width: number): Promise<string> {
  const { default: QRCode } = await import("qrcode");
  return QRCode.toDataURL(uri, {
    width,
    margin: 3,
    color: { dark: "#09090b", light: "#ffffff" },
    errorCorrectionLevel: "M",
  });
}
