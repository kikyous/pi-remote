import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import type { Blob, Item, Text, ToolDiff, Usage } from "../../protocol.ts";
import { makeRef } from "../../refs.ts";
import { clamp, clampThinking, MAX_TEXT_BYTES } from "../../shorten.ts";

export { clamp, cutToBytes, MAX_TEXT_BYTES } from "../../shorten.ts";

/**
 * Session entries → the item list the client renders.
 *
 * This is the **single definition of what a finished item looks like**. The live
 * path in `live/translate.ts` streams the pieces of an item as they arrive, but
 * when the message lands it converts the persisted entry through here and sends
 * the result — so the two paths cannot drift apart by construction.
 *
 * Everything the client used to derive for itself happens here: pairing tool
 * calls with their results, picking the one argument worth showing on a
 * collapsed row, recognising an edit call as a diff, and shortening anything
 * oversized. The client no longer knows a single one of pi's conventions.
 */

/**
 * Arguments worth showing on a collapsed tool row. A path or a command says far
 * more than whichever key happens to come first.
 */
const HEADLINE_KEYS = ["command", "file_path", "filePath", "path", "pattern", "query", "url"];

/** A tool result, indexed by the id of the call it answers. */
interface FoundResult {
	entryId: string;
	message: Record<string, unknown>;
}

export function itemsFromEntries(entries: SessionEntry[]): Item[] {
	// A result is a separate entry that lands after the call that produced it, so
	// index the results first and let each call claim its own on the way through.
	const results = new Map<string, FoundResult>();
	for (const entry of entries) {
		const record = entry as unknown as Record<string, unknown>;
		if (record.type !== "message" || typeof record.id !== "string") continue;
		const message = record.message;
		if (!isRecord(message) || message.role !== "toolResult") continue;
		const callId = str(message.toolCallId);
		if (callId) results.set(callId, { entryId: record.id, message });
	}

	// Claimed results are rendered as part of the call's own row, so the standalone
	// entry for them is skipped. A result whose call sits on an older page is never
	// claimed, and shows up on its own rather than dropping the output on the floor.
	const claimed = new Set<string>();
	const items: Item[] = [];
	for (const entry of entries) {
		items.push(...fromEntry(entry as unknown as Record<string, unknown>, results, claimed));
	}
	return items;
}

function fromEntry(entry: Record<string, unknown>, results: Map<string, FoundResult>, claimed: Set<string>): Item[] {
	const id = typeof entry.id === "string" ? entry.id : undefined;
	if (!id) return [];
	const at = typeof entry.timestamp === "string" ? entry.timestamp : "";

	switch (entry.type) {
		case "message":
			return isRecord(entry.message) ? fromMessage(id, at, entry.message, results, claimed) : [];
		case "compaction":
			return [{ kind: "notice", id, at, note: "compaction" }];
		case "branch_summary":
			return [{ kind: "notice", id, at, note: "branch" }];
		case "model_change":
			return [{ kind: "notice", id, at, note: "model", arg: `${str(entry.provider) ?? ""}/${str(entry.modelId) ?? ""}` }];
		case "thinking_level_change":
			return [{ kind: "notice", id, at, note: "thinking", arg: str(entry.thinkingLevel) ?? "" }];
		case "session_info": {
			const name = str(entry.name);
			return name ? [{ kind: "notice", id, at, note: "named", arg: name }] : [];
		}
		default:
			// label, custom, and anything pi adds later: no conversation content.
			// Unknown kinds are dropped *here*, which is why the client can use a
			// closed set of item kinds without risking a blank screen.
			return [];
	}
}

function fromMessage(
	entryId: string,
	at: string,
	message: Record<string, unknown>,
	results: Map<string, FoundResult>,
	claimed: Set<string>,
): Item[] {
	switch (message.role) {
		case "user": {
			const images = imagesOf(entryId, message.content);
			return [
				{
					kind: "user",
					id: entryId,
					at,
					text: textOf(entryId, message.content),
					...(images.length > 0 ? { images } : {}),
				},
			];
		}

		case "assistant":
			return fromAssistant(entryId, at, message, results, claimed);

		case "toolResult": {
			const callId = str(message.toolCallId);
			// Claimed by the assistant item that called it; already rendered there.
			if (callId && claimed.has(callId)) return [];
			const fields = resultFields(entryId, message);
			return [
				{
					kind: "tool",
					id: entryId,
					at,
					name: str(message.toolName) ?? "",
					output: fields.output,
					...(callId ? { callId } : {}),
					...(fields.isError ? { isError: true } : {}),
					...(fields.hasImage ? { hasImage: true } : {}),
				},
			];
		}

		case "bashExecution": {
			const output = str(message.output) ?? "";
			return [
				{
					kind: "tool",
					id: entryId,
					at,
					name: "bash",
					title: str(message.command) ?? "",
					output: clamp(output, entryId, "output"),
					...(typeof message.exitCode === "number" ? { exit: message.exitCode } : {}),
				},
			];
		}

		case "custom": {
			const text = plainText(message.content);
			return text.trim().length > 0 ? [{ kind: "notice", id: entryId, at, note: "text", arg: text }] : [];
		}

		case "compactionSummary":
			return [{ kind: "notice", id: entryId, at, note: "compaction" }];
		case "branchSummary":
			return [{ kind: "notice", id: entryId, at, note: "branch" }];

		default:
			return [];
	}
}

function fromAssistant(
	entryId: string,
	at: string,
	message: Record<string, unknown>,
	results: Map<string, FoundResult>,
	claimed: Set<string>,
): Item[] {
	const blocks = Array.isArray(message.content) ? message.content : [];
	let text = "";
	let thinking: Text | undefined;
	const calls: Array<Extract<Item, { kind: "tool" }>> = [];

	blocks.forEach((raw, index) => {
		if (!isRecord(raw)) return;
		switch (raw.type) {
			case "text":
				text += str(raw.text) ?? "";
				break;
			case "thinking":
				// Only the first thinking block is shown; the UI has one card.
				thinking ??= clampThinking(str(raw.thinking) ?? "", entryId, index);
				break;
			case "toolCall":
				calls.push(toolItemFromCall(entryId, at, raw, index, results, claimed));
				break;
		}
	});

	const usage = usageOf(message.usage);
	const error = str(message.errorMessage);
	const assistant: Extract<Item, { kind: "assistant" }> = {
		kind: "assistant",
		id: entryId,
		at,
		text: clamp(text, entryId, "text", firstTextIndex(blocks)),
		...(thinking ? { thinking } : {}),
		...(usage ? { usage } : {}),
		...(error ? { error } : {}),
	};

	// The calls follow their assistant message, in call order. Adjacency is what
	// the renderer groups on.
	return [assistant, ...calls];
}

/**
 * The block index the assistant's text came from.
 *
 * Concatenated text needs one ref for "show all", and every real message has a
 * single text block; when there are several, the first is the useful anchor.
 */
function firstTextIndex(blocks: unknown[]): number {
	const at = blocks.findIndex((b) => isRecord(b) && b.type === "text");
	return at === -1 ? 0 : at;
}

/**
 * One tool row: the call, plus its result once that has landed.
 *
 * The row's id is derived from the assistant entry and the block index rather
 * than the result's entry id, so it is stable from the moment the call appears —
 * a `running` row does not change identity when its output arrives.
 */
export function toolItemFromCall(
	entryId: string,
	at: string,
	block: Record<string, unknown>,
	index: number,
	results: Map<string, FoundResult>,
	claimed: Set<string>,
): Extract<Item, { kind: "tool" }> {
	const callId = str(block.id) ?? str(block.toolCallId) ?? "";
	const args = isRecord(block.arguments) ? block.arguments : undefined;

	const item: Extract<Item, { kind: "tool" }> = {
		kind: "tool",
		id: `${entryId}#${index}`,
		at,
		name: str(block.name) ?? str(block.toolName) ?? "",
		output: { s: "" },
	};
	if (callId) item.callId = callId;
	if (args) {
		const title = headline(args);
		if (title !== undefined) item.title = title;
		item.args = clamp(prettyArgs(args), entryId, "arguments", index);
		const diff = editDiff(args);
		if (diff) item.diff = diff;
	}

	const found = callId ? results.get(callId) : undefined;
	if (!found) {
		item.running = true;
		return item;
	}
	claimed.add(callId);

	const fields = resultFields(found.entryId, found.message);
	item.output = fields.output;
	if (fields.isError) item.isError = true;
	if (fields.hasImage) item.hasImage = true;
	return item;
}

/** The parts of a `toolResult` message a tool row shows. */
export function resultFields(
	entryId: string,
	message: Record<string, unknown>,
): { output: Text; isError: boolean; hasImage: boolean } {
	if (typeof message.content === "string") {
		return {
			output: clamp(message.content, entryId, "text"),
			isError: message.isError === true,
			hasImage: false,
		};
	}

	const blocks = Array.isArray(message.content) ? message.content : [];
	let text = "";
	let textIndex = 0;
	let seenText = false;
	let hasImage = false;

	blocks.forEach((raw, index) => {
		if (!isRecord(raw)) return;
		if (raw.type === "text") {
			if (!seenText) {
				textIndex = index;
				seenText = true;
			}
			text += str(raw.text) ?? "";
		} else if (raw.type === "image") {
			hasImage = true;
		}
	});

	return {
		output: clamp(text, entryId, "text", textIndex),
		isError: message.isError === true,
		hasImage,
	};
}

/* ---------------- field helpers ---------------- */

function textOf(entryId: string, content: unknown): Text {
	if (typeof content === "string") return clamp(content, entryId, "text");
	if (!Array.isArray(content)) return { s: "" };
	const at = content.findIndex((b) => isRecord(b) && b.type === "text");
	return clamp(plainText(content), entryId, "text", at === -1 ? 0 : at);
}

/** Concatenated text of a content array, or the string itself. */
function plainText(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.filter((b): b is Record<string, unknown> => isRecord(b) && b.type === "text")
		.map((b) => str(b.text) ?? "")
		.join("");
}

/**
 * Image placeholders only — a single `read` on a PNG produced 361KB of base64,
 * by far the largest thing in the corpus. The bytes come from `/full?ref=` when
 * the user actually looks.
 */
function imagesOf(entryId: string, content: unknown): Blob[] {
	if (!Array.isArray(content)) return [];
	const out: Blob[] = [];
	content.forEach((raw, index) => {
		if (!isRecord(raw) || raw.type !== "image") return;
		const data = str(raw.data) ?? (isRecord(raw.source) ? str(raw.source.data) : undefined);
		out.push({
			ref: makeRef(entryId, "image", index),
			mime: str(raw.mimeType) ?? (isRecord(raw.source) ? str(raw.source.mediaType) ?? "image/png" : "image/png"),
			bytes: data?.length ?? 0,
		});
	});
	return out;
}

function usageOf(raw: unknown): Usage | undefined {
	if (!isRecord(raw)) return undefined;
	const cost = isRecord(raw.cost) && typeof raw.cost.total === "number" ? raw.cost.total : 0;
	const usage: Usage = {
		in: num(raw.input),
		out: num(raw.output),
		cacheRead: num(raw.cacheRead),
		cost,
	};
	// An all-zero usage is what a failed or aborted turn reports; not worth a line.
	if (usage.in === 0 && usage.out === 0 && usage.cacheRead === 0 && usage.cost <= 0) return undefined;
	return usage;
}

/** One `k: v` line per argument. Nested values keep their JSON form. */
export function prettyArgs(args: Record<string, unknown>): string {
	return Object.entries(args)
		.map(([key, value]) => `${key}: ${typeof value === "string" ? value : JSON.stringify(value)}`)
		.join("\n");
}

export function headline(args: Record<string, unknown>): string | undefined {
	for (const key of HEADLINE_KEYS) {
		const found = str(args[key]);
		if (found) return found;
	}
	const first = Object.values(args)[0];
	return typeof first === "string" ? first : undefined;
}

/**
 * An edit call carries `{path, edits: [{oldText, newText}, …]}`.
 *
 * Recognised by the edits array whatever the tool is called, so a renamed edit
 * tool keeps rendering as a diff.
 */
export function editDiff(args: Record<string, unknown>): ToolDiff | undefined {
	if (!Array.isArray(args.edits)) return undefined;
	const hunks: ToolDiff["hunks"] = [];
	for (const raw of args.edits) {
		if (!isRecord(raw)) continue;
		const old = str(raw.oldText);
		const next = str(raw.newText);
		if (old === undefined || next === undefined) continue;
		hunks.push({ old, new: next });
	}
	if (hunks.length === 0) return undefined;
	const path = str(args.path) ?? str(args.file_path);
	return { ...(path ? { path } : {}), hunks };
}

function str(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

function num(value: unknown): number {
	return typeof value === "number" ? value : 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}
