import { ModelRuntime } from "@earendil-works/pi-coding-agent";

import { acquire, getLoaded, withPromptLock } from "./agent-pool.ts";
import { HttpError } from "./http.ts";
import {
	type ModelDto,
	type ModelsResponseDto,
	type PromptResultDto,
	THINKING_LEVELS,
	type ThinkingLevel,
} from "./protocol.ts";
import { invalidateSessionCache } from "./store.ts";

/** Shown for a model whose session is not loaded, so exact support is unknown. */
const STANDARD_LEVELS = ["off", "minimal", "low", "medium", "high"];

let runtime: ModelRuntime | undefined;

async function getRuntime(): Promise<ModelRuntime> {
	runtime ??= await ModelRuntime.create();
	return runtime;
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
): Promise<PromptResultDto> {
	if (typeof text !== "string" || text.trim().length === 0) {
		throw new HttpError(400, "message must be a non-empty string", "empty_message");
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

	if (updated.length > 0) invalidateSessionCache();
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
