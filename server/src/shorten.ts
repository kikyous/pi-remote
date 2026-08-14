import type { Text } from "./protocol.ts";
import { type FullPart, makeRef } from "./refs.ts";

/**
 * The size budget every backend shortens to.
 *
 * Shared rather than per-backend on purpose: the limits are about what a phone
 * can hold and how much of a payload is worth spending on one row, which has
 * nothing to do with which agent produced the text. The measured corpus that
 * set them: a 2.7MB / 990-entry session whose largest single entry was **361KB**
 * — paging alone still gets blown up by one tool result, so both shortening and
 * paging are required.
 */

/** Text longer than this is cut. Above ordinary tool output, well under a phone-sized payload. */
export const MAX_TEXT_BYTES = 8 * 1024;

/** Thinking is collapsed by default in the UI, so only a teaser travels. */
export const THINKING_PREVIEW_CHARS = 200;

/** Cut `value` to the byte budget, attaching a ref for the rest when it did not fit. */
export function clamp(value: string, ownerId: string, part: FullPart, index?: number): Text {
	const bytes = Buffer.byteLength(value);
	if (bytes <= MAX_TEXT_BYTES) return { s: value };
	return { s: cutToBytes(value, MAX_TEXT_BYTES), more: { ref: makeRef(ownerId, part, index), bytes } };
}

export function clampThinking(value: string, ownerId: string, index: number): Text {
	if (value.length <= THINKING_PREVIEW_CHARS) return { s: value };
	return {
		s: value.slice(0, THINKING_PREVIEW_CHARS),
		more: { ref: makeRef(ownerId, "thinking", index), bytes: Buffer.byteLength(value) },
	};
}

/** Cut to a byte budget without splitting a multi-byte character. */
export function cutToBytes(text: string, maxBytes: number): string {
	const buf = Buffer.from(text, "utf8");
	if (buf.length <= maxBytes) return text;
	// `toString` on a slice ending mid-character yields U+FFFD; drop it.
	const cut = buf.subarray(0, maxBytes).toString("utf8");
	return cut.endsWith("�") ? cut.slice(0, -1) : cut;
}
