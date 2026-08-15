import type { AgentBackend, NewSessionOptions, SessionHandle, SessionPatch } from "../../backend.ts";
import type { ItemPageDto, ModelsResponseDto, PromptImageDto, PromptResultDto } from "../../protocol.ts";
import {
	acquire,
	createSession,
	createWorkspace,
	disposeAll,
	getLoaded,
	isRunning,
	liveTree,
	setResyncHandler,
} from "./agent-pool.ts";
import {
	abortSession,
	compactSession,
	generateSessionTitle,
	idleStatus,
	listModels,
	loadedSessionState,
	sendPrompt,
	updateSession,
} from "./commands.ts";
import { deleteSession, deleteWorkspace } from "./delete.ts";
import { setLiveSource } from "./model.ts";
import {
	getDetail,
	getFullByRef,
	getItemPage,
	itemPageOf,
	listProjects,
	listSessions,
	requireLocated,
	setLiveStateProbe,
	setRunningProbe,
} from "./store.ts";

/**
 * The pi backend: `@earendil-works/pi-coding-agent` driven in-process.
 *
 * Browsing reads `~/.pi/agent/sessions` JSONL directly and starts nothing; an
 * `AgentSession` is created only when someone prompts, and recycled when nobody
 * is watching. That split is what makes switching sessions instant, and it is
 * why [open] and [getItemPage] are separate from [acquire].
 */
export function createPiBackend(): AgentBackend {
	// The read-only layer cannot import the pool (that would be a cycle), so the
	// three lookups it needs are injected. They stay inside this backend now that
	// both halves live under the same directory.
	setRunningProbe(isRunning);
	setLiveSource(liveTree);
	setLiveStateProbe(loadedSessionState);

	return {
		kind: "pi",

		listProjects,
		listSessions,

		async open(sessionId: string): Promise<SessionHandle> {
			// Both awaits happen here so the snapshot itself can be taken without
			// yielding — see `SessionHandle`.
			const [located, detail] = await Promise.all([requireLocated(sessionId), getDetail(sessionId)]);
			// A session with no agent loaded still has a context bar to fill, and
			// only the file can answer for it.
			const idle = getLoaded(sessionId) ? undefined : await idleStatus(sessionId, detail.model ?? null);
			return {
				detail,
				idleStatus: idle,
				itemPage: (before, limit): ItemPageDto => itemPageOf(located, before, limit),
			};
		},

		getItemPage,
		getDetail,
		getFullByRef,
		listModels: (): Promise<ModelsResponseDto> => listModels(),

		createSession: (cwd: string, options: NewSessionOptions): Promise<string> => createSession(cwd, options),
		createWorkspace,
		deleteSession,
		deleteWorkspace,

		prompt: (
			sessionId: string,
			text: string,
			streamingBehavior: "steer" | "followUp" | undefined,
			images: PromptImageDto[] | undefined,
		): Promise<PromptResultDto> => sendPrompt(sessionId, text, streamingBehavior, images),
		abort: abortSession,
		updateSession: async (sessionId: string, patch: SessionPatch): Promise<void> => {
			await updateSession(sessionId, patch);
		},
		generateTitle: async (sessionId: string): Promise<void> => {
			await generateSessionTitle(sessionId);
		},
		compact: compactSession,

		acquire: (sessionId: string) => acquire(sessionId),
		getLoaded,
		setResyncHandler,

		dispose: disposeAll,
	};
}
