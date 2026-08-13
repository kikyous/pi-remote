/**
 * Wire debug mode (`--debug`): logs every message exchanged with the app.
 *
 * Two directions, logged at the chokepoints they cross:
 *   ←  HTTP request / WebSocket command coming from the app
 *   →  HTTP response / WebSocket push going to the app
 *
 * The switch is process-wide and set once at startup. That is all the plumbing
 * a single-process bridge serving a handful of devices needs.
 */

/** Longest single payload printed; anything bigger (base64 images, item pages) is cut. */
const MAX_PRINT_CHARS = 4096;

let enabled = false;

/** Turn wire logging on or off. Called once at startup. */
export function setDebug(on: boolean): void {
	enabled = on;
}

export function isDebug(): boolean {
	return enabled;
}

/** Print one debug line; a no-op unless `--debug` was passed. */
export function debugLog(line: string): void {
	if (enabled) console.log(`[debug] ${line}`);
}

/** JSON-stringify a payload for the log, cutting huge bodies at MAX_PRINT_CHARS. */
export function formatJson(payload: unknown): string {
	let text: string;
	try {
		text = JSON.stringify(payload);
	} catch {
		text = String(payload);
	}
	return truncate(text);
}

/** Cut `text` at MAX_PRINT_CHARS, noting how much was dropped. */
export function truncate(text: string): string {
	const bytes = Buffer.byteLength(text);
	if (bytes <= MAX_PRINT_CHARS) return text;
	const cut = Buffer.from(text).subarray(0, MAX_PRINT_CHARS).toString("utf8");
	return `${cut}\n… (+${bytes - MAX_PRINT_CHARS} bytes)`;
}

/**
 * A request URL fit for the log, with the shared token scrubbed.
 *
 * The token rides the query string on the WebSocket upgrade (`/ws?token=…`),
 * so the raw URL must never be printed — even in debug mode.
 */
export function redactUrl(url: URL): string {
	const search = new URLSearchParams(url.search);
	if (search.has("token")) search.set("token", "***");
	const qs = search.toString();
	return qs ? `${url.pathname}?${qs}` : url.pathname;
}
