import type { SessionEntry, SessionTreeNode } from "@earendil-works/pi-coding-agent";

import { HttpError } from "../../http.ts";
import type { NavigateResultDto, SessionTreeDto, TreeNodeDto, TreeNodeKind } from "../../protocol.ts";
import { acquire, resync } from "./agent-pool.ts";
import { markRebuilt } from "../../live/hub.ts";
import { withDeadline } from "./commands.ts";
import { headline } from "./items.ts";
import { getModel } from "./model.ts";
import { textOf } from "./scan.ts";
import { requireLocated } from "./store.ts";

/**
 * The session tree, and moving the leaf around it.
 *
 * pi calls this `/tree`, and it is the one thing a session file can do that the
 * conversation view cannot show: every entry has a `parentId`, so a session is a
 * tree, and everything outside the leaf → root path is a branch someone walked
 * away from. Reading the tree is free (the file is already parsed); moving the
 * leaf needs the agent, because the leaf lives in its memory.
 *
 * **The leaf is not persisted.** `SessionManager` sets it to the last line of the
 * file on load, and a navigation that takes no summary writes nothing at all — so
 * a jump survives exactly as long as the agent does, and the branch only becomes
 * real once the next message is appended with its `parentId` pointing at the new
 * leaf. That is pi's own behaviour, not a shortcut taken here.
 */

/**
 * Characters kept per row.
 *
 * A tree row is one line of orientation, not content: `shorten.ts`'s 8KB budget
 * is for reading a message, and a thousand of those would be the whole session
 * on the wire twice over.
 */
const LINE_CHARS = 100;

/** How long we wait for an in-flight run before refusing to navigate. */
const NAVIGATE_IDLE_WAIT_MS = 10_000;

/* ---------------- reading ---------------- */

/**
 * The whole tree of a session, as a nested structure the client can flatten.
 *
 * When an agent *is* loaded, `getModel` reads its in-memory tree instead of the
 * file — which is what makes a navigation visible here at all, since it never
 * reached disk.
 */
export async function sessionTree(sessionId: string): Promise<SessionTreeDto> {
	const model = getModel(await requireLocated(sessionId));
	if (!model) return { nodes: [], leafId: null };
	return { nodes: buildTreeDto(model.tree()), leafId: model.leafId };
}

/**
 * Build the flat DTO from pi's tree nodes.
 *
 * Entries that carry no row (labels already resolved onto their target,
 * extension state) are skipped and their children inherit the nearest valid
 * parentId — so parentId never points at something the client was not sent.
 */
/** Exported for tests. */
export function buildTreeDto(
	nodes: SessionTreeNode[],
	toolCallMap: Map<string, { name: string; arguments: Record<string, unknown> }> = new Map(),
	parentId: string | null = null,
): TreeNodeDto[] {
	const out: TreeNodeDto[] = [];
	for (const node of nodes) {
		// Index assistant tool calls for toolResult lookup, matching TUI.
		const entry = node.entry as unknown as Record<string, unknown>;
		if (entry.type === "message") {
			const msg = entry.message as Record<string, unknown>;
			if (msg?.role === "assistant" && Array.isArray(msg.content)) {
				for (const block of msg.content) {
					if (isRecord(block) && block.type === "toolCall") {
						const id = str(block.id);
						if (id) {
							toolCallMap.set(id, {
								name: str(block.name) ?? "tool",
								arguments: isRecord(block.arguments) ? block.arguments : {},
							});
						}
					}
				}
			}
		}

		const row = describe(node.entry, toolCallMap);
		if (!row) {
			// Skip this node, promote its children so parentId points at the
			// nearest ancestor that was actually emitted.
			out.push(...buildTreeDto(node.children, toolCallMap, parentId));
			continue;
		}
		out.push({
			id: node.entry.id,
			parentId,
			kind: row.kind,
			text: row.text,
			at: node.entry.timestamp,
			...(node.label ? { label: node.label } : {}),
		});
		out.push(...buildTreeDto(node.children, toolCallMap, node.entry.id));
	}
	return out;
}

/**
 * One row's kind and summary line, or undefined for an entry that gets no row.
 *
 * Deliberately the same split `items.ts` makes — entries that carry no
 * conversation are dropped — so the tree cannot show a node the chat view has no
 * way to render once you land on it.
 */
export function describe(
	entry: SessionEntry,
	toolCallMap?: Map<string, { name: string; arguments: Record<string, unknown> }>,
): { kind: TreeNodeKind; text: string } | undefined {
	const record = entry as unknown as Record<string, unknown>;

	switch (record.type) {
		case "message":
			return isRecord(record.message) ? fromMessage(record.message, toolCallMap) : undefined;
		case "compaction":
			return { kind: "compaction", text: `compacted ${tokens(record.tokensBefore)}` };
		case "branch_summary":
			return { kind: "branch", text: line(str(record.summary) ?? "branch summary") };
		case "model_change":
			return { kind: "model", text: `${str(record.provider) ?? ""}/${str(record.modelId) ?? ""}` };
		case "thinking_level_change":
			return { kind: "thinking", text: str(record.thinkingLevel) ?? "" };
		case "session_info":
			return { kind: "named", text: str(record.name) ?? "" };
		case "custom_message":
			return { kind: "custom", text: line(textOf(record.content)) };
		default:
			// `label` (already resolved onto its target by getTree), `custom`
			// (extension state, never in context), and anything pi adds later.
			return undefined;
	}
}

function fromMessage(
	message: Record<string, unknown>,
	toolCallMap?: Map<string, { name: string; arguments: Record<string, unknown> }>,
): { kind: TreeNodeKind; text: string } {
	switch (message.role) {
		case "user":
			return { kind: "user", text: line(textOf(message.content)) };
		case "assistant": {
			const text = line(textOf(message.content));
			if (text) return { kind: "assistant", text };
			// A turn that only called tools. pi's own tree hides these; here they
			// are named after their calls instead, so the row is never blank and
			// the client can still filter them out as tool noise.
			const calls = toolCalls(message.content);
			return calls ? { kind: "tool", text: calls } : { kind: "assistant", text: stalled(message) };
		}
		case "toolResult": {
			const callId = str(message.toolCallId);
			const toolCall = callId && toolCallMap ? toolCallMap.get(callId) : undefined;
			if (toolCall) {
				return { kind: "toolResult", text: formatToolCall(toolCall.name, toolCall.arguments) };
			}
			const name = str(message.toolName) ?? "tool";
			return { kind: "toolResult", text: `[${name}]` };
		}
		case "bashExecution":
			return { kind: "bash", text: line(str(message.command) ?? "") };
		case "branchSummary":
			return { kind: "branch", text: line(str(message.summary) ?? "branch summary") };
		case "compactionSummary":
			return { kind: "compaction", text: `compacted ${tokens(message.tokensBefore)}` };
		default:
			return { kind: "custom", text: line(textOf(message.content)) };
	}
}

/** The calls an assistant turn made, each with its one identifying argument. */
function toolCalls(content: unknown): string | undefined {
	if (!Array.isArray(content)) return undefined;
	const named: string[] = [];
	for (const block of content) {
		if (!isRecord(block) || block.type !== "toolCall") continue;
		const name = str(block.name) ?? "tool";
		const arg = isRecord(block.arguments) ? headline(block.arguments) : undefined;
		named.push(arg ? `${name}(${arg})` : name);
	}
	return named.length > 0 ? line(named.join(", ")) : undefined;
}

/** Why an assistant message has nothing to show. */
function stalled(message: Record<string, unknown>): string {
	if (message.stopReason === "aborted") return "(aborted)";
	const error = str(message.errorMessage);
	return error ? line(error) : "(no content)";
}

/** Collapse to one line and cut it to [LINE_CHARS] real characters. */
function line(text: string): string {
	const flat = text.replace(/\s+/g, " ").trim();
	const chars = Array.from(flat);
	return chars.length > LINE_CHARS ? `${chars.slice(0, LINE_CHARS).join("")}…` : flat;
}

function tokens(raw: unknown): string {
	const count = typeof raw === "number" ? raw : 0;
	return count >= 1000 ? `${Math.round(count / 1000)}k tokens` : `${count} tokens`;
}

/** Format a tool call the way TUI does: [name: arg]. */
function formatToolCall(name: string, args: Record<string, unknown>): string {
	switch (name) {
		case "read": {
			const path = str(args.path ?? args.file_path) ?? "";
			const offset = args.offset;
			const limit = args.limit;
			let display = path;
			if (offset !== undefined || limit !== undefined) {
				const start = typeof offset === "number" ? offset : 1;
				const end = typeof limit === "number" ? start + limit - 1 : "";
				display += `:${start}${end ? `-${end}` : ""}`;
			}
			return `[read: ${display}]`;
		}
		case "write": {
			const path = str(args.path ?? args.file_path) ?? "";
			return `[write: ${path}]`;
		}
		case "edit": {
			const path = str(args.path ?? args.file_path) ?? "";
			return `[edit: ${path}]`;
		}
		case "bash": {
			const rawCmd = str(args.command) ?? "";
			const cmd = rawCmd.replace(/[\n\t]/g, " ").trim().slice(0, 50);
			return `[bash: ${cmd}${rawCmd.length > 50 ? "..." : ""}]`;
		}
		case "grep": {
			const pattern = str(args.pattern) ?? "";
			const path = str(args.path) ?? ".";
			return `[grep: /${pattern}/ in ${path}]`;
		}
		case "find": {
			const pattern = str(args.pattern) ?? "";
			const path = str(args.path) ?? ".";
			return `[find: ${pattern} in ${path}]`;
		}
		case "ls": {
			const path = str(args.path) ?? ".";
			return `[ls: ${path}]`;
		}
		default: {
			const argsStr = JSON.stringify(args).slice(0, 40);
			return `[${name}: ${argsStr}${JSON.stringify(args).length > 40 ? "..." : ""}]`;
		}
	}
}

/* ---------------- navigating ---------------- */

/**
 * Move the leaf to [entryId] and continue from there.
 *
 * Needs the agent: the leaf is in its memory, and it has to rebuild its context
 * from the new path. Refuses a busy session rather than interrupting it — pi's
 * `navigateTree` throws on a streaming session anyway, and killing a turn the
 * user is watching is not what tapping a node asks for.
 */
export async function navigateTree(sessionId: string, entryId: string): Promise<NavigateResultDto> {
	const live = await acquire(sessionId, true);
	const manager = live.session.sessionManager;

	if (!manager.getEntry(entryId)) {
		throw new HttpError(404, `No entry ${entryId} in session ${sessionId}`, "entry_not_found");
	}
	if (manager.getLeafId() === entryId) {
		throw new HttpError(409, "Already at this point", "already_at_leaf");
	}

	// Same ten seconds of grace as compaction, for a turn that is just finishing.
	await withDeadline(
		live.session.agent.waitForIdle(),
		NAVIGATE_IDLE_WAIT_MS,
		new HttpError(409, "Session is running, please try again later", "session_busy"),
	);

	// No `summarize`: a branch summary is the only part of navigation that writes
	// to the file, and with the leaf deliberately not persisted there is nothing
	// for it to preserve. The option stays available for later.
	const result = await live.session.navigateTree(entryId);
	if (result.cancelled) {
		// An extension's `session_before_tree` hook said no.
		throw new HttpError(409, "Navigation was cancelled", "navigate_cancelled");
	}

	// The leaf moved backwards, so the item list *shrank* — and `add`/`patch` can
	// only express growth. Two things are needed, because they cover different
	// clients: the mark invalidates every cursor issued before now, so a device
	// that was away cannot come back and be "caught up" onto a branch that is no
	// longer there; the resync pushes a fresh snapshot to whoever is watching
	// right now, so their screen changes immediately rather than on next refresh.
	markRebuilt(live);
	resync(sessionId);

	return {
		leafId: manager.getLeafId(),
		...(result.editorText !== undefined ? { editorText: result.editorText } : {}),
	};
}

function str(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}
