/**
 * Shrink session entries before they go over the network.
 *
 * Measured on real sessions (79 files, largest 2.7MB / 984 messages), the
 * oversized things are, in order:
 *
 *   361KB  toolResult with an `image` block (read on a PNG → base64)
 *    45KB  toolResult text (read on a large file)
 *    31KB  toolResult text (bash output)
 *    25KB  assistant toolCall arguments (write with full file content)
 *    12KB  assistant thinking block
 *
 * All five are handled here. The client shows a placeholder with the real size
 * and fetches the original from `/entries/{id}/full` on demand.
 */

/** Text longer than this is cut. Chosen to sit above ordinary tool output but well under a phone-sized payload. */
const MAX_TEXT_BYTES = 8 * 1024;

/** Thinking is collapsed by default in the UI, so only a teaser travels. */
const THINKING_PREVIEW_CHARS = 200;

/** Identifies one shrunk part of an entry, for the `/full` endpoint. */
export type FullPart = "text" | "thinking" | "image" | "arguments" | "output";

interface Truncated {
	truncated: true;
	/** Byte length of the original, for "show all (45 KB)" affordances. */
	fullLength: number;
	/** Index into the message's content array; absent for whole-message parts. */
	index?: number;
	part: FullPart;
}

export function slimEntry(entry: unknown): unknown {
	if (!isRecord(entry)) return entry;

	switch (entry.type) {
		case "message":
			return { ...entry, message: slimMessage(entry.message) };
		case "compaction":
			return slimCompaction(entry);
		case "branch_summary":
			return { ...entry, summary: clampString(entry.summary) };
		case "custom_message":
			return { ...entry, content: slimContent(entry.content) };
		default:
			// model_change, thinking_level_change, label, session_info, custom —
			// all small and structural. `custom.data` is extension-defined, so it
			// still gets a generic pass.
			return entry.type === "custom" && entry.data !== undefined
				? { ...entry, data: clampDeep(entry.data) }
				: entry;
	}
}

/**
 * Shrink an agent event before it goes out over the WebSocket.
 *
 * Live events carry the same payloads as stored entries — `entry_appended`
 * holds a whole SessionEntry, `message_end` a whole message, `tool_execution_end`
 * a whole result — so without this the 361KB image would simply arrive by a
 * different route.
 */
export function slimEvent(event: unknown): unknown {
	if (!isRecord(event)) return event;

	switch (event.type) {
		case "entry_appended":
			return { ...event, entry: slimEntry(event.entry) };
		case "message_start":
		case "message_end":
			return { ...event, message: slimMessage(event.message) };
		case "turn_end":
			return {
				...event,
				message: slimMessage(event.message),
				...(Array.isArray(event.toolResults) ? { toolResults: event.toolResults.map(slimMessage) } : {}),
			};
		case "agent_end":
			return Array.isArray(event.messages) ? { ...event, messages: event.messages.map(slimMessage) } : event;
		case "tool_execution_start":
			return { ...event, args: clampDeep(event.args) };
		case "tool_execution_update":
			return { ...event, args: clampDeep(event.args), partialResult: slimToolPayload(event.partialResult) };
		case "tool_execution_end":
			return { ...event, result: slimToolPayload(event.result) };
		case "message_update":
			// Deltas are small by construction, and re-walking every one of them
			// would cost more than it saves.
			return event;
		default:
			return event;
	}
}

/** Tool results and partial results share the `{content, details}` shape. */
function slimToolPayload(payload: unknown): unknown {
	if (!isRecord(payload)) return payload;
	const out: Record<string, unknown> = { ...payload };
	if (payload.content !== undefined) out.content = slimContent(payload.content);
	if (payload.details !== undefined) out.details = clampDeep(payload.details);
	return out;
}

function slimMessage(message: unknown): unknown {
	if (!isRecord(message)) return message;

	switch (message.role) {
		case "assistant":
			return { ...message, content: slimAssistantBlocks(message.content) };
		case "toolResult":
			return {
				...message,
				content: slimContent(message.content),
				...(message.details !== undefined ? { details: clampDeep(message.details) } : {}),
			};
		case "bashExecution":
			return slimBashExecution(message);
		case "user":
		case "custom":
			return { ...message, content: slimContent(message.content) };
		case "compactionSummary":
		case "branchSummary":
			return { ...message, summary: clampString(message.summary) };
		default:
			return message;
	}
}

function slimAssistantBlocks(content: unknown): unknown {
	if (!Array.isArray(content)) return content;
	return content.map((block, index) => {
		if (!isRecord(block)) return block;
		switch (block.type) {
			case "thinking":
				return slimThinking(block, index);
			case "text":
				return slimText(block, index);
			case "toolCall":
				return slimToolCall(block, index);
			case "image":
				return stripImage(block, index);
			default:
				return block;
		}
	});
}

/** user / toolResult / custom content: string, or an array of text+image blocks. */
function slimContent(content: unknown): unknown {
	if (typeof content === "string") return clampString(content);
	if (!Array.isArray(content)) return content;
	return content.map((block, index) => {
		if (!isRecord(block)) return block;
		if (block.type === "image") return stripImage(block, index);
		if (block.type === "text") return slimText(block, index);
		return block;
	});
}

function slimThinking(block: Record<string, unknown>, index: number): unknown {
	const thinking = typeof block.thinking === "string" ? block.thinking : "";
	const fullLength = Buffer.byteLength(thinking);
	if (thinking.length <= THINKING_PREVIEW_CHARS) return block;
	return {
		...block,
		thinking: thinking.slice(0, THINKING_PREVIEW_CHARS),
		truncated: true,
		fullLength,
		index,
		part: "thinking",
	} satisfies Record<string, unknown> & Truncated;
}

function slimText(block: Record<string, unknown>, index: number): unknown {
	const text = typeof block.text === "string" ? block.text : "";
	const fullLength = Buffer.byteLength(text);
	if (fullLength <= MAX_TEXT_BYTES) return block;
	return {
		...block,
		text: cutToBytes(text, MAX_TEXT_BYTES),
		truncated: true,
		fullLength,
		index,
		part: "text",
	} satisfies Record<string, unknown> & Truncated;
}

/**
 * Drop image payloads entirely.
 *
 * A single read on a PNG produced a 361KB base64 block — by far the largest
 * item in the corpus. The client shows a placeholder; MVP has no image viewer,
 * and when one arrives it can fetch the original.
 */
function stripImage(block: Record<string, unknown>, index: number): unknown {
	const data = typeof block.data === "string" ? block.data : undefined;
	// pi's on-disk shape is `{type, data, mimeType}`; some paths nest it under
	// `source`. Handle both so nothing slips through with its payload attached.
	const nested = isRecord(block.source) && typeof block.source.data === "string" ? block.source.data : undefined;
	const payload = data ?? nested;
	if (payload === undefined) return block;

	const stripped: Record<string, unknown> = { ...block, truncated: true, fullLength: payload.length, index, part: "image" };
	if (data !== undefined) stripped.data = "";
	if (nested !== undefined && isRecord(block.source)) stripped.source = { ...block.source, data: "" };
	return stripped;
}

/**
 * Truncate long string values inside tool arguments, field by field.
 *
 * Not the whole object: `write` puts the entire file in `content` but the
 * useful `file_path` sits right next to it, and the UI needs that to render
 * "▸ write  src/foo.ts" without a round trip.
 */
function slimToolCall(block: Record<string, unknown>, index: number): unknown {
	const args = block.arguments;
	if (!isRecord(args)) return block;

	let changed = false;
	const slimmed: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(args)) {
		if (typeof value === "string" && Buffer.byteLength(value) > MAX_TEXT_BYTES) {
			slimmed[key] = cutToBytes(value, MAX_TEXT_BYTES);
			changed = true;
		} else {
			const next = clampDeep(value);
			if (next !== value) changed = true;
			slimmed[key] = next;
		}
	}
	if (!changed) return block;

	return {
		...block,
		arguments: slimmed,
		truncated: true,
		fullLength: Buffer.byteLength(JSON.stringify(args)),
		index,
		part: "arguments",
	} satisfies Record<string, unknown> & Truncated;
}

function slimBashExecution(message: Record<string, unknown>): unknown {
	const output = typeof message.output === "string" ? message.output : "";
	const fullLength = Buffer.byteLength(output);
	if (fullLength <= MAX_TEXT_BYTES) return message;
	return {
		...message,
		output: cutToBytes(output, MAX_TEXT_BYTES),
		truncated: true,
		fullLength,
		part: "output",
	} satisfies Record<string, unknown> & Truncated;
}

/**
 * A compaction entry carries `retainedTail`, a materialized message array that
 * can be as heavy as the conversation it replaced.
 */
function slimCompaction(entry: Record<string, unknown>): unknown {
	const out: Record<string, unknown> = { ...entry, summary: clampString(entry.summary) };
	if (Array.isArray(entry.retainedTail)) out.retainedTail = entry.retainedTail.map(slimMessage);
	if (entry.details !== undefined) out.details = clampDeep(entry.details);
	return out;
}

/** Generic guard for extension-defined blobs: clamp any long string, at any depth. */
function clampDeep(value: unknown, depth = 0): unknown {
	if (depth > 8) return value;
	if (typeof value === "string") return clampString(value);
	if (Array.isArray(value)) return value.map((v) => clampDeep(v, depth + 1));
	if (isRecord(value)) {
		const out: Record<string, unknown> = {};
		for (const [k, v] of Object.entries(value)) out[k] = clampDeep(v, depth + 1);
		return out;
	}
	return value;
}

function clampString(value: unknown): unknown {
	if (typeof value !== "string") return value;
	return Buffer.byteLength(value) > MAX_TEXT_BYTES ? cutToBytes(value, MAX_TEXT_BYTES) : value;
}

/** Cut to a byte budget without splitting a multi-byte character. */
function cutToBytes(text: string, maxBytes: number): string {
	const buf = Buffer.from(text, "utf8");
	if (buf.length <= maxBytes) return text;
	// `toString` on a slice that ends mid-character yields U+FFFD; drop it.
	const cut = buf.subarray(0, maxBytes).toString("utf8");
	return cut.endsWith("�") ? cut.slice(0, -1) : cut;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Pull one original part back out of an un-slimmed entry, for `/full`.
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
