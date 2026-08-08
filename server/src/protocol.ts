/**
 * Wire types shared with the Android client.
 *
 * Keep this file dependency-free and structural: it is the contract, and the
 * Kotlin side mirrors it by hand. Anything added here needs a matching change
 * in `android/.../net/Protocol.kt`.
 */

export const API_PREFIX = "/api/v1";

/**
 * Mirrors `ThinkingLevel` from `@earendil-works/pi-agent-core`, which is a
 * transitive dependency rather than one of ours. Structurally identical, so it
 * assigns straight into the SDK's setters.
 *
 * Note the SDK has two similarly named types: pi-ai's `ThinkingLevel` omits
 * "off" (that one is `ModelThinkingLevel`). This one — the agent-core one — is
 * what `AgentSession.setThinkingLevel()` takes.
 */
export type ThinkingLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";

export const THINKING_LEVELS: ThinkingLevel[] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];

/** A working directory that has at least one session. */
export interface ProjectDto {
	/** Absolute path the sessions were started in. */
	cwd: string;
	/** Last path segment, for display. */
	name: string;
	sessionCount: number;
	/** ISO timestamp of the most recently modified session in this cwd. */
	lastModified: string;
}

/** One session in a list. Deliberately smaller than the SDK's SessionInfo. */
export interface SessionSummaryDto {
	id: string;
	cwd: string;
	/** User-defined display name (`/name`), if set. */
	name?: string;
	created: string;
	modified: string;
	messageCount: number;
	/** First user message, truncated for display. */
	firstMessage: string;
	/** Set when this session was forked from another. */
	parentSessionId?: string;
}

/** Full state for one session, including live agent state when running. */
export interface SessionDetailDto extends SessionSummaryDto {
	/** Current model as recorded in the session, or null if never set. */
	model: { provider: string; modelId: string } | null;
	thinkingLevel: string;
	/** Current position in the entry tree; null for an empty session. */
	leafId: string | null;
	totalEntries: number;
	/** True when an AgentSession is loaded and streaming. */
	running: boolean;
	/**
	 * Exact levels the current model accepts. Present only while an agent is
	 * loaded — otherwise the client falls back to the set from `/models`.
	 */
	availableThinkingLevels?: string[];
}

export interface EntryPageDto {
	/** Oldest-first within the page. */
	entries: unknown[];
	/** True when older entries exist before `oldestId`. */
	hasMore: boolean;
	/** Cursor to pass as `before` for the next older page; null when empty. */
	oldestId: string | null;
	/** Current leaf, so the client can tell whether the active branch moved. */
	leafId: string | null;
}

export interface ModelDto {
	provider: string;
	id: string;
	name: string;
	reasoning: boolean;
	contextWindow?: number;
	/** Levels the picker may offer. A loaded session reports the exact set. */
	thinkingLevels: string[];
}

export interface ModelsResponseDto {
	models: ModelDto[];
}

export interface PromptResultDto {
	accepted: true;
	/** True when the agent was already running and the message went to a queue. */
	queued: boolean;
}

/**
 * How a client should decide a prompt is "done".
 *
 * A normal prompt runs `agent_start … agent_settled`, and `agent_settled` is
 * the signal to clear the busy indicator. An **extension command** (`/askme`,
 * any `/name` registered by an extension) does NOT: it executes inline and
 * emits only whatever messages its handler produces — verified against
 * `piremote-demo.ts`, which yields a single `custom` message and no lifecycle
 * events at all.
 *
 * So a client must not gate its spinner on `agent_settled` alone, or a slash
 * command leaves it spinning forever. Treat the HTTP 200 as "accepted", and
 * clear busy on `agent_settled` **or** on the absence of `agent_start` shortly
 * after acceptance.
 */
export type PromptCompletionNote = never;

/* ---------- WebSocket ---------- */

/** Client → server. */
export type WsCommand =
	| { op: "subscribe"; sessionId: string; sinceSeq?: number }
	| { op: "unsubscribe"; sessionId: string }
	| { op: "ping" };

/** Server → client. */
export type WsMessage =
	| {
			op: "event";
			sessionId: string;
			/** Monotonic per session; a jump means events were missed. */
			seq: number;
			entryId?: string;
			event: unknown;
	  }
	| {
			op: "subscribed";
			sessionId: string;
			/** Sequence the client is current as of, after any replay. */
			seq: number;
			/** True when the gap was too large to replay — refetch the page. */
			gap: boolean;
			running: boolean;
	  }
	| { op: "unsubscribed"; sessionId: string }
	| { op: "pong" }
	| { op: "error"; message: string; code?: string; sessionId?: string };

export interface ErrorDto {
	error: string;
	/** Machine-readable discriminator, e.g. "session_busy". */
	code?: string;
}
