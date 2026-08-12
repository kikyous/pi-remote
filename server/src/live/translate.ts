import type { AgentSessionEvent, SessionEntry } from "@earendil-works/pi-coding-agent";

import { itemsFromEntries, resultFields } from "../items.ts";
import type { ContextUsageDto, Item, ItemPatch, SessionStatus, Text } from "../protocol.ts";

/**
 * The SDK's event stream → mutations of the item list.
 *
 * **This is the only file in the server that knows what an `AgentSessionEvent`
 * looks like.** Everything downstream — the WebSocket, the replay buffer, the
 * client — deals in items and patches. That boundary is the point: the previous
 * design forwarded SDK events verbatim, so the client had to understand
 * `assistantMessageEvent.type`, `partialResult.content[].text` and
 * `message.usage.cost.total`, and 51% of the bytes on the wire turned out to be
 * events it never read at all.
 *
 * The final shape of an item is *not* defined here. When a message lands, the
 * persisted entry goes through `itemsFromEntries()` — the same function the read
 * path uses — so the streamed view and the stored view cannot drift.
 */

/** A push without its `sessionId`/`seq`, which the pool assigns. */
export type Mutation =
	| { t: "add"; item: Item }
	| { t: "patch"; id: string; append?: { f: "text" | "thinking" | "output"; s: string }; set?: ItemPatch }
	| { t: "status"; status: SessionStatus };

/** What the translator cannot know by itself, supplied by the pool. */
export interface StatusProbe {
	running: boolean;
	context?: ContextUsageDto;
}

export interface Translator {
	handle(event: AgentSessionEvent): void;
	/** The in-flight assistant item, for a client subscribing mid-run. */
	tail(): Item | undefined;
	status(): SessionStatus;
	/** Re-read the probe and emit a status push if anything moved. */
	syncStatus(): void;
}

interface Streaming {
	id: string;
	text: string;
	thinking: string;
	/** Which field deltas currently belong to. */
	phase: "thinking" | "text";
	at: string;
}

export function createTranslator(emit: (m: Mutation) => void, probe: () => StatusProbe): Translator {
	let minted = 0;
	let streaming: Streaming | undefined;
	/** toolCallId → the id of the row that call created, so results can patch it. */
	const rows = new Map<string, string>();
	/** toolCallId → the output already streamed, so a result is not resent verbatim. */
	const streamed = new Map<string, string>();
	/** Calls whose live tail hit [LIVE_OUTPUT_LIMIT], so the client needs the handle. */
	const capped = new Set<string>();
	let status: SessionStatus = { running: false, queued: [], compacting: false };

	function syncStatus(): void {
		const now = probe();
		const next: SessionStatus = {
			running: now.running,
			queued: status.queued,
			compacting: status.compacting,
			...(now.context ? { context: now.context } : {}),
		};
		if (sameStatus(status, next)) return;
		status = next;
		emit({ t: "status", status: next });
	}

	function setStatus(patch: Partial<SessionStatus>): void {
		const next = { ...status, ...patch };
		if (sameStatus(status, next)) return;
		status = next;
		emit({ t: "status", status: next });
	}

	function handle(event: AgentSessionEvent): void {
		const e = event as unknown as Record<string, unknown>;

		switch (e.type) {
			case "message_start": {
				// Only an assistant message gets a placeholder to stream into.
				// Everything else (user, toolResult, bash) is emitted from its
				// persisted entry, which arrives a microtask later and carries the
				// refs and truncation markers that events do not have.
				const message = asRecord(e.message);
				if (message?.role !== "assistant") break;
				minted += 1;
				streaming = {
					id: `live-${minted}`,
					text: "",
					thinking: "",
					phase: "text",
					at: new Date(numberOr(message.timestamp, Date.now())).toISOString(),
				};
				emit({ t: "add", item: { kind: "assistant", id: streaming.id, at: streaming.at, text: { s: "" }, pending: true } });
				break;
			}

			case "message_update": {
				const inner = asRecord(e.assistantMessageEvent);
				if (!inner || !streaming) break;
				const delta = typeof inner.delta === "string" ? inner.delta : "";
				switch (inner.type) {
					case "thinking_start":
						streaming.phase = "thinking";
						break;
					case "text_start":
						streaming.phase = "text";
						break;
					case "thinking_delta":
						if (delta) {
							streaming.thinking += delta;
							emit({ t: "patch", id: streaming.id, append: { f: "thinking", s: delta } });
						}
						break;
					case "text_delta":
						if (delta) {
							streaming.text += delta;
							emit({ t: "patch", id: streaming.id, append: { f: "text", s: delta } });
						}
						break;
					// toolcall_start/delta/end, text_end, thinking_end: the tool row
					// is built from the persisted entry, which has the complete
					// arguments. Streaming the arguments JSON was 17% of the old
					// wire and the client never read a byte of it.
				}
				break;
			}

			case "message_end": {
				// An extension command's output is a `custom` message that nothing ever
				// persists: after `/askme` the session file does not even exist. There
				// is no entry to reconcile with, so it is emitted straight from the
				// event with a minted id, and will not survive a resync — honest,
				// because there is nothing on disk to resync it from.
				//
				// `/askme` itself yields `content: []` (with `hasUI === false` it takes
				// its non-interactive path and produces nothing), so in practice this
				// fires only for extensions that do emit text. Previously such text had
				// no way to reach the chat at all.
				const message = asRecord(e.message);
				if (message?.role !== "custom") break;
				const text = textOfContent(message.content);
				if (text.trim().length === 0) break;
				minted += 1;
				emit({
					t: "add",
					item: { kind: "notice", id: `live-${minted}`, at: new Date().toISOString(), note: "text", arg: text },
				});
				break;
			}

			case "entry_appended":
				onEntry(e.entry as SessionEntry);
				break;

			case "tool_execution_update": {
				const callId = stringOr(e.toolCallId);
				const row = callId ? rows.get(callId) : undefined;
				if (!callId || !row) break;
				// `partialResult` is cumulative — the whole output so far, resent on
				// every update. Send only what is new, or a long-running command
				// costs O(n²) on the wire.
				const full = textOfPayload(e.partialResult);
				const already = streamed.get(callId) ?? "";
				if (full.startsWith(already) && full.length > already.length) {
					if (already.length >= LIVE_OUTPUT_LIMIT) {
						// The live tail stops growing here, so the client's copy is a
						// prefix; the result's `more` handle reaches the rest.
						capped.add(callId);
						break;
					}
					streamed.set(callId, full);
					emit({ t: "patch", id: row, append: { f: "output", s: full.slice(already.length) } });
				} else if (full !== already) {
					// The tool rewrote its output rather than extending it.
					streamed.set(callId, full);
					emit({ t: "patch", id: row, set: { output: { s: full } } });
				}
				break;
			}

			case "tool_execution_end": {
				const callId = stringOr(e.toolCallId);
				const row = callId ? rows.get(callId) : undefined;
				if (row) emit({ t: "patch", id: row, set: { running: false } });
				break;
			}

			case "queue_update":
				setStatus({ queued: [...stringList(e.steering), ...stringList(e.followUp)] });
				break;

			case "compaction_start":
				setStatus({ compacting: true });
				break;
			case "compaction_end":
				setStatus({ compacting: false });
				break;
		}

		// Running-ness is read from the session itself rather than inferred from
		// agent_start/agent_settled. An extension command (`/askme`) emits neither,
		// which is exactly why the old client could be left spinning forever.
		syncStatus();
	}

	/**
	 * A persisted entry landed: reconcile it with whatever was streamed.
	 *
	 * The assistant message being streamed is patched rather than re-added, so its
	 * id — and the client's list key — never changes. Its text is sent again only
	 * if it does not match what we streamed, which is the escape hatch for a
	 * retry or a redaction rather than the normal path.
	 */
	function onEntry(entry: SessionEntry): void {
		const items = itemsFromEntries([entry]);
		if (items.length === 0) return;

		const record = entry as unknown as Record<string, unknown>;
		const message = asRecord(record.message);
		const first = items[0]!;

		// A tool result belongs to a row that already exists.
		if (message?.role === "toolResult") {
			const callId = stringOr(message.toolCallId);
			const row = callId ? rows.get(callId) : undefined;
			if (row && typeof record.id === "string" && callId) {
				const fields = resultFields(record.id, message);
				const already = streamed.get(callId) ?? "";
				const set: ItemPatch = {
					running: false,
					...(fields.isError ? { isError: true } : {}),
					...(fields.hasImage ? { hasImage: true } : {}),
				};
				if (needsResend(fields.output, already)) {
					set.output = fields.output;
				} else if (fields.output.more && capped.has(callId)) {
					// The client streamed a prefix and stopped; hand it the handle
					// alone rather than resending what it already has.
					set.output = { more: fields.output.more };
				}
				emit({ t: "patch", id: row, set });
				streamed.delete(callId);
				capped.delete(callId);
				return;
			}
			// No row to patch (its call is not in this run): show it standalone.
			for (const item of items) emit({ t: "add", item });
			return;
		}

		if (message?.role === "assistant" && streaming && first.kind === "assistant") {
			const set: ItemPatch = { pending: false };
			if (needsResend(first.text, streaming.text)) set.text = first.text;
			if (needsResend(first.thinking, streaming.thinking)) set.thinking = first.thinking;
			if (first.usage) set.usage = first.usage;
			if (first.error) set.error = first.error;
			emit({ t: "patch", id: streaming.id, set });

			// The calls this message made are new rows.
			for (const item of items.slice(1)) {
				if (item.kind === "tool" && item.callId) rows.set(item.callId, item.id);
				emit({ t: "add", item });
			}
			streaming = undefined;
			return;
		}

		for (const item of items) {
			if (item.kind === "tool" && item.callId) rows.set(item.callId, item.id);
			emit({ t: "add", item });
		}
	}

	function tail(): Item | undefined {
		if (!streaming) return undefined;
		const item: Item = {
			kind: "assistant",
			id: streaming.id,
			at: streaming.at,
			text: { s: streaming.text },
			pending: true,
			...(streaming.thinking ? { thinking: { s: streaming.thinking } } : {}),
		};
		return item;
	}

	return { handle, tail, status: () => status, syncStatus };
}

/**
 * How much of a running tool's output the live tail carries.
 *
 * Bounds what a phone accumulates while watching a command that prints
 * megabytes. The full output is reachable through the result's `more` ref.
 */
const LIVE_OUTPUT_LIMIT = 8 * 1024;

/**
 * Whether the authoritative field has to be sent again.
 *
 * Normally not: the client already has every character, because it received the
 * same deltas we accumulated. Two cases do need it — nothing was streamed at
 * all, and genuine drift (a retry, a redaction) where the stored text is not
 * what we streamed.
 *
 * A field the store *shortened* is not drift. Its `s` is a prefix of what we
 * streamed, and the client's fuller copy is better than the preview plus a "show
 * all" ref — so it is deliberately left in place.
 */
function needsResend(text: Text | undefined, streamed: string): boolean {
	if (!text) return false;
	if (streamed.length === 0) return text.s.length > 0;
	return text.more === undefined ? text.s !== streamed : !streamed.startsWith(text.s);
}

function sameStatus(a: SessionStatus, b: SessionStatus): boolean {
	return (
		a.running === b.running &&
		a.compacting === b.compacting &&
		a.queued.length === b.queued.length &&
		a.queued.every((q, i) => q === b.queued[i]) &&
		a.context?.tokens === b.context?.tokens &&
		a.context?.percent === b.context?.percent
	);
}

/** Tool payloads are `{content: [{type:"text", text}], details}`. */
function textOfPayload(payload: unknown): string {
	const record = asRecord(payload);
	return record ? textOfContent(record.content) : "";
}

/** Message content is a bare string, or an array of blocks of which text ones count. */
function textOfContent(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.map((block) => {
			const b = asRecord(block);
			return b && b.type === "text" && typeof b.text === "string" ? b.text : "";
		})
		.join("");
}

function stringList(value: unknown): string[] {
	return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
	return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;
}

function stringOr(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

function numberOr(value: unknown, fallback: number): number {
	return typeof value === "number" ? value : fallback;
}
