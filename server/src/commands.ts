import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { Agent, type AgentMessage } from "@earendil-works/pi-agent-core";

import { acquire, getLoaded, withPromptLock } from "./agent-pool.ts";
import { HttpError } from "./http.ts";
import {
	type ContextUsageDto,
	type ModelDto,
	type ModelsResponseDto,
	type PromptImageDto,
	type PromptResultDto,
	THINKING_LEVELS,
	type ThinkingLevel,
} from "./protocol.ts";
import { estimateSessionTokens } from "./store.ts";

/** Shown for a model whose session is not loaded, so exact support is unknown. */
const STANDARD_LEVELS = ["off", "minimal", "low", "medium", "high"];

let runtime: ModelRuntime | undefined;

async function getRuntime(): Promise<ModelRuntime> {
	runtime ??= await ModelRuntime.create();
	return runtime;
}

/**
 * Token usage of the active branch.
 *
 * A loaded agent knows the exact usage from its last LLM response; otherwise
 * the file is replayed through the SDK's estimator and the context window is
 * looked up from the model registry.
 */
export async function sessionContextUsage(
	sessionId: string,
	model: { provider: string; modelId: string } | null,
): Promise<ContextUsageDto> {
	const live = getLoaded(sessionId);
	if (live) {
		const usage = live.session.getContextUsage();
		if (usage) return usage;
	}

	const tokens = await estimateSessionTokens(sessionId);
	let contextWindow: number | null = null;
	if (model) {
		try {
			const m = (await getRuntime()).getModel(model.provider, model.modelId);
			contextWindow = m?.contextWindow ?? null;
		} catch {
			contextWindow = null;
		}
	}
	const percent =
		tokens !== null && contextWindow !== null && contextWindow > 0
			? Math.round((tokens / contextWindow) * 1000) / 10
			: null;
	return { tokens, contextWindow, percent };
}

export async function listModels(): Promise<ModelsResponseDto> {
	const rt = await getRuntime();
	const available = await rt.getAvailable();

	const models: ModelDto[] = available.map((model) => ({
		provider: model.provider,
		id: model.id,
		name: model.name,
		reasoning: Boolean(model.reasoning),
		contextWindow: model.contextWindow,
		// Exact levels depend on the model's API and are only knowable from a
		// live session; this is the safe superset the picker can show meanwhile.
		thinkingLevels: model.reasoning ? STANDARD_LEVELS : ["off"],
	}));

	return { models };
}

/**
 * Send a prompt without waiting for the run to finish.
 *
 * `session.prompt()` resolves only after the whole run completes — which can be
 * minutes — so the HTTP response hangs off `preflightResult`, which fires as
 * soon as the prompt is accepted or rejected. Errors raised after acceptance
 * travel on the event stream, not on this response.
 */
export async function sendPrompt(
	sessionId: string,
	text: string,
	streamingBehavior: "steer" | "followUp" | undefined,
	images: PromptImageDto[] | undefined,
): Promise<PromptResultDto> {
	if (typeof text !== "string" || (text.trim().length === 0 && !images?.length)) {
		throw new HttpError(400, "message must be a non-empty string", "empty_message");
	}
	if (images?.length && text.trim().length === 0) {
		text = ""; // an attachment-only message is legal; pi handles the bare image
	}

	const live = await acquire(sessionId, true);

	// The busy check and the prompt call must be atomic per session — see
	// `withPromptLock`. Checking outside the lock loses concurrent messages.
	return withPromptLock(live, async () => {
		const wasStreaming = live.session.isStreaming;

		if (wasStreaming && streamingBehavior === undefined) {
			throw new HttpError(
				409,
				"Session is running. Send streamingBehavior 'steer' to interrupt or 'followUp' to queue.",
				"session_busy",
			);
		}

		let settled = false;
		const accepted = await new Promise<boolean>((resolve, reject) => {
			const finish = (value: boolean) => {
				if (settled) return;
				settled = true;
				resolve(value);
			};

			live.session
				.prompt(text, {
					...(streamingBehavior ? { streamingBehavior } : {}),
					...(images?.length ? { images } : {}),
					preflightResult: finish,
				})
				.then(() => finish(true))
				.catch((err: unknown) => {
					if (settled) {
						// The run failed after we already answered; the client sees
						// it through the event stream.
						console.error(`[${sessionId}] run failed:`, err);
						return;
					}
					settled = true;
					reject(err instanceof Error ? err : new Error(String(err)));
				});
		});

		if (!accepted) throw new HttpError(409, "Prompt was rejected before acceptance", "prompt_rejected");

		return { accepted: true as const, queued: wasStreaming };
	});
}

export async function abortSession(sessionId: string): Promise<{ aborted: boolean }> {
	const live = getLoaded(sessionId);
	// Nothing loaded, or nothing running, means nothing to stop. Not an error:
	// the client is often just racing a run that ended on its own.
	if (!live || !live.session.isStreaming) return { aborted: false };
	await live.session.abort();
	return { aborted: true };
}

export interface SessionPatch {
	provider?: string;
	modelId?: string;
	thinkingLevel?: string;
	name?: string;
}

/** How long the title model may take before we give up. */
const TITLE_TIMEOUT_MS = 90_000;

/** How long we wait for an in-flight run before refusing the title request. */
const TITLE_IDLE_WAIT_MS = 10_000;

/** Same prompt pi-web uses, so titles read alike across clients. */
const TITLE_PROMPT = `Create a concise title for this session based on the conversation above.

Requirements:
- Match the primary language used by the user.
- Describe the user's concrete goal or the outcome, not the act of chatting.
- Use 4-12 words for space-separated languages, or 8-24 characters for CJK text when practical.
- Do not call any tools.
- Return only the title as plain text, with no quotes, label, markdown, or explanation.`;

/**
 * Ask the session's model for a short title derived from the conversation,
 * then persist it through the normal rename path.
 *
 * Direct port of pi-web's auto-name route: a fresh pi-agent-core `Agent` is
 * spawned from the parent session's config (system prompt, model, thinking
 * level, disabled tools) and either `continue()`s from the last user message
 * or `prompt()`s the title rule — so generating a title never adds an entry
 * to the conversation.
 */
export async function generateSessionTitle(sessionId: string): Promise<{ title: string }> {
	const live = await acquire(sessionId, true);
	const parent = live.session.agent;

	// Refuse while the session is busy: waiting out a long run would hang the
	// phone's request. Ten seconds of grace for a run that is just finishing.
	await withDeadline(
		parent.waitForIdle(),
		TITLE_IDLE_WAIT_MS,
		new HttpError(409, "会话正在运行，请稍后再试", "session_busy"),
	);

	// Drop tool calls without a following result (and their orphan results): a
	// run may be mid-flight when the title is requested.
	const paired = pairToolResults(parent.state.messages);
	const originalCount = paired.length;
	if (!paired.some((m) => m.role === "user")) {
		throw new HttpError(400, "会话还没有用户消息", "empty_session");
	}

	// Like pi-web: when the turn ended on a user message, append the rule to it
	// and continue; otherwise prompt with the rule as a fresh user message.
	const lastIsUser = paired[paired.length - 1]?.role === "user";
	const initialState = {
		systemPrompt: parent.state.systemPrompt,
		model: parent.state.model,
		thinkingLevel: parent.state.thinkingLevel,
		tools: parent.state.tools.map((tool) => ({
			...tool,
			execute: async () => {
				throw new Error("Tools cannot be executed while generating a session title");
			},
		})),
		messages: (lastIsUser
			? (() => {
					const last = paired[paired.length - 1]!;
					const content =
						typeof (last as { content?: unknown }).content === "string"
							? `${(last as { content: string }).content}\n\n${TITLE_PROMPT}`
							: [
									...(last as { content: Array<{ type: string; text?: string }> }).content,
									{ type: "text" as const, text: TITLE_PROMPT },
							  ];
					return [...paired.slice(0, -1), { ...last, content }];
				})()
			: paired) as AgentMessage[],
	};

	const titleAgent = new Agent({
		initialState,
		convertToLlm: parent.convertToLlm,
		transformContext: parent.transformContext,
		streamFn: parent.streamFunction,
		getApiKey: parent.getApiKey,
		onPayload: parent.onPayload,
		onResponse: parent.onResponse,
		steeringMode: parent.steeringMode,
		followUpMode: parent.followUpMode,
		sessionId: parent.sessionId,
		thinkingBudgets: parent.thinkingBudgets,
		transport: parent.transport,
		maxRetryDelayMs: parent.maxRetryDelayMs,
		toolExecution: parent.toolExecution,
	});

	const run = lastIsUser ? titleAgent.continue() : titleAgent.prompt(TITLE_PROMPT);
	let timer: ReturnType<typeof setTimeout> | undefined;
	try {
		await Promise.race([
			run,
			new Promise<never>((_, reject) => {
				timer = setTimeout(() => {
					titleAgent.abort();
					reject(new HttpError(504, "标题生成超时", "title_timeout"));
			}, TITLE_TIMEOUT_MS);
			}),
		]);
	} catch (err) {
		titleAgent.abort();
		await run.catch(() => {});
		throw err;
	} finally {
		if (timer) clearTimeout(timer);
	}

	// The title lives in the assistant messages the run appended.
	const appended = titleAgent.state.messages.slice(originalCount);
	for (let i = appended.length - 1; i >= 0; i--) {
		const m = appended[i];
		if (!m || m.role !== "assistant") continue;
		const assistant = m as { stopReason?: string; errorMessage?: string; content: Array<{ type?: string; text?: string }> };
		if (assistant.stopReason === "error") {
			throw new HttpError(502, assistant.errorMessage || "标题模型请求失败", "title_model_error");
		}
		const text = assistant.content
			.filter((b) => b.type === "text")
			.map((b) => b.text ?? "")
			.join("\n")
			.trim();
		if (text) {
			const title = cleanTitle(text);
			await updateSession(sessionId, { name: title });
			return { title };
		}
	}
	throw new HttpError(502, "模型没有返回会话标题", "no_title");
}

/**
 * Keep only tool calls that have a following result, and drop orphan tool
 * results — same pairing rule pi-web applies before asking for a title.
 */
function pairToolResults<T extends { role: string }>(messages: T[]): T[] {
	const out: T[] = [];
	let claimed = new Set<string>();
	for (let i = 0; i < messages.length; i++) {
		const m = messages[i];
		if (!m) continue;
		if (m.role === "assistant") {
			const following = new Set<string>();
			for (let j = i + 1; j < messages.length; j++) {
				const next = messages[j] as { role?: string; toolCallId?: string };
				if (next.role !== "toolResult") break;
				if (next.toolCallId) following.add(next.toolCallId);
			}
			const content = ((m as { content?: unknown }).content as Array<{ type?: string; id?: string }>)
				.filter(
					(block) =>
						block.type !== "toolCall" ||
						(!!block.id && following.has(block.id) && (claimed.add(block.id), true)),
				);
			if (content.length > 0) out.push({ ...m, content });
			continue;
		}
		if (m.role === "toolResult") {
			const callId = (m as { toolCallId?: string }).toolCallId;
			if (callId && claimed.delete(callId)) out.push(m);
			continue;
		}
		out.push(m);
	}
	return out;
}

/**
 * Clean the model's answer into a title — ported from pi-web's auto-name
 * route: strip fences, JSON envelopes, prefixes, surrounding quotes and
 * trailing punctuation; validate it has actual letters; cap at 80 characters.
 */
function cleanTitle(raw: string): string {
	let t = raw.trim();
	const fence = t.match(/^```(?:json|text)?\s*([\s\S]*?)\s*```$/i);
	if (fence) t = (fence[1] ?? "").trim();
	if (t.startsWith("{")) {
		try {
			const parsed = JSON.parse(t) as { title?: unknown };
			if (typeof parsed.title === "string") t = parsed.title.trim();
		} catch {
			/* not JSON */
		}
	}
	t = (t.split(/\r?\n/, 1)[0] ?? "").replace(/^(?:session\s+title|title|标题)\s*[:：-]\s*/i, "");
	for (const [left, right] of [
		['"', '"'],
		["'", "'"],
		["`", "`"],
		["“", "”"],
		["「", "」"],
		["『", "』"],
	] as const) {
		if (t.startsWith(left) && t.endsWith(right) && t.length > left.length + right.length) {
			t = t.slice(left.length, -right.length).trim();
			break;
		}
	}
	t = t.replace(/\s+/g, " ").trim().replace(/[。.!：:，,、；;？?]+$/u, "").trim();
	if (!/[\p{L}\p{N}]/u.test(t)) {
		throw new HttpError(502, "模型没有返回可用标题", "bad_title");
	}
	const chars = Array.from(t);
	if (chars.length > 80) t = chars.slice(0, 80).join("").trim();
	return t;
}



export async function updateSession(sessionId: string, patch: SessionPatch): Promise<{ updated: string[] }> {
	const updated: string[] = [];

	// Every one of these mutates the session file, so an agent has to exist
	// and its in-memory tree must be current.
	const live = await acquire(sessionId, true);

	if (patch.provider !== undefined || patch.modelId !== undefined) {
		if (patch.provider === undefined || patch.modelId === undefined) {
			throw new HttpError(400, "provider and modelId must be sent together", "incomplete_model");
		}
		const model = (await getRuntime()).getModel(patch.provider, patch.modelId);
		if (!model) {
			throw new HttpError(400, `Unknown model ${patch.provider}/${patch.modelId}`, "unknown_model");
		}
		await live.session.setModel(model);
		updated.push("model");
	}

	if (patch.thinkingLevel !== undefined) {
		if (!THINKING_LEVELS.includes(patch.thinkingLevel as ThinkingLevel)) {
			throw new HttpError(400, `thinkingLevel must be one of ${THINKING_LEVELS.join(", ")}`, "bad_thinking_level");
		}
		const supported = live.session.getAvailableThinkingLevels();
		if (!supported.includes(patch.thinkingLevel as ThinkingLevel)) {
			throw new HttpError(
				400,
				`Current model supports only ${supported.join(", ")}`,
				"unsupported_thinking_level",
			);
		}
		live.session.setThinkingLevel(patch.thinkingLevel as ThinkingLevel);
		updated.push("thinkingLevel");
	}

	if (patch.name !== undefined) {
		if (typeof patch.name !== "string") throw new HttpError(400, "name must be a string", "bad_name");
		live.session.setSessionName(patch.name);
		updated.push("name");
	}

	// No cache to invalidate: each of these appends an entry, which moves the
	// file's `(mtime, size)` — the key both session caches validate against.
	return { updated };
}

/**
 * Live state from a loaded agent, which outranks what the file says.
 *
 * A brand-new session has no file yet, and a session whose model was just
 * changed may not have flushed the entry — in both cases the agent in memory is
 * the truth. Returns undefined when no agent is loaded, and the file-derived
 * values stand.
 */
export function loadedSessionState(sessionId: string):
	| { model: { provider: string; modelId: string } | null; thinkingLevel: string; availableThinkingLevels: string[] }
	| undefined {
	const live = getLoaded(sessionId);
	if (!live) return undefined;
	const model = live.session.model;
	return {
		model: model ? { provider: model.provider, modelId: model.id } : null,
		thinkingLevel: live.session.thinkingLevel,
		availableThinkingLevels: live.session.getAvailableThinkingLevels(),
	};
}

/** Reject with [error] if [promise] does not settle within [ms]. */
function withDeadline<T>(promise: Promise<T>, ms: number, error: Error): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		const timer = setTimeout(() => reject(error), ms);
		promise.then(
			(v) => {
				clearTimeout(timer);
				resolve(v);
			},
			(e) => {
				clearTimeout(timer);
				reject(e);
			},
		);
	});
}
