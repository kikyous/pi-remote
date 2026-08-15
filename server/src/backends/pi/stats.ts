import type { SessionEntry } from "@earendil-works/pi-coding-agent";

import type { MessageCountsDto, SessionStatsDto, TokenTotalsDto } from "../../protocol.ts";
import { sessionContextUsage } from "./commands.ts";
import { getModel } from "./model.ts";
import { getDetail, requireLocated } from "./store.ts";

/**
 * What a session has spent.
 *
 * Counted off the entry tree rather than asked of the agent: `getSessionStats()`
 * exists on `AgentSession`, but reaching it would mean loading an agent for a
 * question that is pure arithmetic over a file already parsed and cached. The
 * totals follow the SDK's own rules so the two agree — see [totalsOf].
 */
export async function sessionStats(sessionId: string): Promise<SessionStatsDto> {
	const [located, detail] = await Promise.all([requireLocated(sessionId), getDetail(sessionId)]);
	const model = getModel(located);

	return {
		id: sessionId,
		file: located.path,
		...(detail.name ? { name: detail.name } : {}),
		// A session created moments ago has no file, so nothing was spent yet.
		...totalsOf(model?.all() ?? []),
		context: await sessionContextUsage(sessionId, detail.model ?? null),
	};
}

/**
 * Fold every entry in the file into message counts, tokens and dollars.
 *
 * A direct port of `AgentSession.getSessionStats()`, and deliberately so: a
 * number here that disagrees with what pi's own `/session` prints would read as
 * one of the two being wrong. Three rules carry it —
 *
 *  - the *whole* tree counts, abandoned branches included: the spending happened;
 *  - a tool call is not an entry, so it is counted inside the assistant message
 *    that made it and stays out of `total`;
 *  - usage rides on assistant messages, on tool results (a tool that called a
 *    model of its own), and on the summary entries compaction leaves behind.
 */
export function totalsOf(entries: SessionEntry[]): Pick<SessionStatsDto, "messages" | "tokens" | "cost"> {
	const messages: MessageCountsDto = { user: 0, assistant: 0, toolCalls: 0, toolResults: 0, total: 0 };
	const tokens: TokenTotalsDto = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 };
	let cost = 0;

	const add = (usage: Usage | undefined): void => {
		if (!usage) return;
		tokens.input += num(usage.input);
		tokens.output += num(usage.output);
		tokens.cacheRead += num(usage.cacheRead);
		tokens.cacheWrite += num(usage.cacheWrite);
		cost += num(usage.cost?.total);
	};

	for (const entry of entries) {
		if (entry.type === "compaction" || entry.type === "branch_summary") {
			add(entry.usage as Usage | undefined);
			continue;
		}
		if (entry.type !== "message") continue;

		messages.total++;
		const message = entry.message as { role: string; content?: unknown; usage?: Usage };
		switch (message.role) {
			case "user":
				messages.user++;
				break;
			case "assistant":
				messages.assistant++;
				if (Array.isArray(message.content)) {
					messages.toolCalls += message.content.filter(
						(block) => typeof block === "object" && block !== null && (block as { type?: string }).type === "toolCall",
					).length;
				}
				add(message.usage);
				break;
			case "toolResult":
				messages.toolResults++;
				add(message.usage);
				break;
		}
	}

	tokens.total = tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
	return { messages, tokens, cost };
}

/**
 * The shape of pi's usage record, structurally.
 *
 * `Usage` lives in `@earendil-works/pi-ai`, a transitive dependency rather than
 * one of ours — the same reason `ThinkingLevel` is mirrored in `protocol.ts`.
 * Every field is optional here because old sessions predate some of them.
 */
interface Usage {
	input?: number;
	output?: number;
	cacheRead?: number;
	cacheWrite?: number;
	cost?: { total?: number };
}

function num(value: number | undefined): number {
	return typeof value === "number" && Number.isFinite(value) ? value : 0;
}
