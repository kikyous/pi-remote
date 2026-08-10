import QRCode from "qrcode";

/**
 * URI scheme the Android app understands when it scans the startup QR code.
 * The app fills the connection form (and connects) straight from the payload,
 * so the user never has to type the 32-char token.
 */
export const CONNECT_SCHEME = "piremote://connect";

export function buildConnectPayload(url: string, token: string): string {
	return `${CONNECT_SCHEME}?url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;
}

/**
 * Render the connection info as an ASCII QR code for the terminal.
 *
 * `small` packs the modules into ANSI half-blocks, which keeps the code narrow
 * enough to fit a typical 80-col console while staying scannable. On failure
 * (e.g. a terminal that mangles the output) the plain payload is returned
 * instead, so setup never dies on a pretty-print error.
 */
export async function renderConnectQr(payload: string): Promise<string> {
	try {
		return await QRCode.toString(payload, { type: "terminal", small: true });
	} catch (err) {
		console.error("QR generation failed, printing payload instead:", err);
		return payload;
	}
}
