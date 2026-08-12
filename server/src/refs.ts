/**
 * Handles for content that was shortened before it went over the wire.
 *
 * A ref is opaque to the client: it arrives inside an item, and comes back on
 * `GET /full?ref=`. Only this file knows the format, which is what collapsed the
 * old `{entryId, part, index}` triple — and the `FullPart` enum that had to be
 * mirrored in Kotlin — into a single string the client never inspects.
 */

/** Which piece of an entry a ref points at. */
export type FullPart = "text" | "thinking" | "image" | "arguments" | "output";

const PARTS = new Set<FullPart>(["text", "thinking", "image", "arguments", "output"]);

/**
 * `<entryId>|<part>|<index>`.
 *
 * Entry ids are ULIDs, so the separator cannot occur in one. The index is empty
 * for whole-message parts like a bash execution's output.
 */
export function makeRef(entryId: string, part: FullPart, index?: number): string {
	return `${entryId}|${part}|${index ?? ""}`;
}

export interface ParsedRef {
	entryId: string;
	part: FullPart;
	index: number | undefined;
}

export function parseRef(ref: string): ParsedRef | undefined {
	const bits = ref.split("|");
	if (bits.length !== 3) return undefined;
	const [entryId, part, rawIndex] = bits as [string, string, string];
	if (entryId.length === 0 || !PARTS.has(part as FullPart)) return undefined;
	if (rawIndex.length === 0) return { entryId, part: part as FullPart, index: undefined };
	const index = Number(rawIndex);
	if (!Number.isInteger(index) || index < 0) return undefined;
	return { entryId, part: part as FullPart, index };
}

/**
 * Pull one original part back out of an un-shortened entry.
 * Returns undefined when the coordinates do not resolve.
 */
export function extractFullPart(entry: unknown, part: FullPart, index: number | undefined): string | undefined {
	if (!isRecord(entry)) return undefined;
	const message = isRecord(entry.message) ? entry.message : entry;

	if (part === "output") return typeof message.output === "string" ? message.output : undefined;

	const content = message.content;
	if (!Array.isArray(content) || index === undefined) return undefined;
	const block = content[index];
	if (!isRecord(block)) return undefined;

	switch (part) {
		case "text":
			return typeof block.text === "string" ? block.text : undefined;
		case "thinking":
			return typeof block.thinking === "string" ? block.thinking : undefined;
		case "arguments":
			return block.arguments === undefined ? undefined : JSON.stringify(block.arguments);
		case "image": {
			if (typeof block.data === "string") return block.data;
			return isRecord(block.source) && typeof block.source.data === "string" ? block.source.data : undefined;
		}
		default:
			return undefined;
	}
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}
