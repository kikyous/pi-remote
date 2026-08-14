import type { LiveSession } from "./live/hub.ts";
import type {
	ItemPageDto,
	ModelsResponseDto,
	ProjectDto,
	PromptImageDto,
	PromptResultDto,
	SessionDetailDto,
	SessionStatus,
	SessionSummaryDto,
} from "./protocol.ts";

/**
 * The seam between the wire and whichever coding agent is behind it.
 *
 * `protocol.ts` is what the app speaks and it mentions no agent at all — the
 * compatibility burden sits here, in the one place able to carry it. Everything
 * above this line (`http.ts`, `ws.ts`, `git.ts`, `live/hub.ts`,
 * `live/coalesce.ts`) is written against these methods and never imports a
 * backend module; everything a specific agent knows lives under `backends/`.
 *
 * The four injection seams this replaced (`setRunningProbe`, `setLiveSource`,
 * `setLiveStateProbe`, `setResyncHandler`) were opened to break import cycles.
 * They already had the right shape — this is the same idea, named.
 */

export interface NewSessionOptions {
	provider?: string;
	modelId?: string;
	thinkingLevel?: string;
}

export interface SessionPatch {
	provider?: string;
	modelId?: string;
	thinkingLevel?: string;
	name?: string;
}

export interface WorkspaceResult {
	id: string;
	cwd: string;
	created: boolean;
}

/**
 * An opened session, from which the rest of a snapshot is read **synchronously**.
 *
 * That is the whole reason this type exists. A `hello` has to read the item page
 * and the live sequence number in the same tick: with an `await` between them, a
 * mutation landing in the gap ends up in neither the snapshot nor the pushes
 * released afterwards, and its message is gone for good. So every await a
 * backend needs — a file lookup, an RPC round trip — happens in [AgentBackend.open],
 * and what comes back can answer without yielding.
 */
export interface SessionHandle {
	/** The settings half of the session. What it is *doing* travels as status. */
	readonly detail: SessionDetailDto;
	/**
	 * Status for a session with no agent loaded — nothing is running by
	 * definition, but the context bar still wants a number. Undefined when an
	 * agent was loaded at open time, since `snapshotPoint` then reports instead.
	 */
	readonly idleStatus: SessionStatus | undefined;
	/** One page of the conversation, newest-last, walking back from `before`. */
	itemPage(before: string | undefined, limit: number): ItemPageDto;
}

export interface AgentBackend {
	readonly kind: "pi";

	/* ── browsing: must never start an agent ───────────────────────────────── */

	listProjects(): Promise<ProjectDto[]>;
	listSessions(cwd: string): Promise<SessionSummaryDto[]>;
	/**
	 * Open a session for a snapshot. Costs whatever the backend needs to answer
	 * synchronously afterwards, so the paging route uses [getItemPage] instead.
	 */
	open(sessionId: string): Promise<SessionHandle>;
	/** One page on its own, for `GET /sessions/:id/items`. */
	getItemPage(sessionId: string, before: string | undefined, limit: number): Promise<ItemPageDto>;
	getDetail(sessionId: string): Promise<SessionDetailDto>;
	/** The original behind a `more` handle, whatever kind of content it points at. */
	getFullByRef(sessionId: string, ref: string): Promise<{ content: string }>;
	listModels(): Promise<ModelsResponseDto>;

	/* ── execution ─────────────────────────────────────────────────────────── */

	createSession(cwd: string, options: NewSessionOptions): Promise<string>;
	/** The one-tap daily workspace; the path is derived entirely server-side. */
	createWorkspace(): Promise<WorkspaceResult>;
	deleteSession(sessionId: string): Promise<{ deleted: number }>;
	deleteWorkspace(cwd: string): Promise<{ deleted: number }>;
	/**
	 * Send a prompt and resolve **as soon as it is admitted**, not when the run
	 * ends. A run that fails afterwards reports on the event stream.
	 */
	prompt(
		sessionId: string,
		text: string,
		streamingBehavior: "steer" | "followUp" | undefined,
		images: PromptImageDto[] | undefined,
	): Promise<PromptResultDto>;
	abort(sessionId: string): Promise<{ aborted: boolean }>;
	updateSession(sessionId: string, patch: SessionPatch): Promise<void>;
	/** Optional capability: throw `HttpError(501)` when the agent cannot do it. */
	generateTitle(sessionId: string): Promise<void>;

	/* ── live sessions ─────────────────────────────────────────────────────── */

	/** Get the live session, creating it if needed. */
	acquire(sessionId: string): Promise<LiveSession>;
	/** The live session if one is loaded; browsing must not force one. */
	getLoaded(sessionId: string): LiveSession | undefined;
	/**
	 * Called when a session was replaced under its subscribers, so they can be
	 * sent a fresh snapshot. Set by `ws.ts`, which is where `hello` is built.
	 */
	setResyncHandler(handler: (sessionId: string) => void): void;

	dispose(): Promise<void>;
}

let current: AgentBackend | undefined;

export function setBackend(backend: AgentBackend): void {
	current = backend;
}

export function backend(): AgentBackend {
	if (!current) throw new Error("backend used before it was selected");
	return current;
}
