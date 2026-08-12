/**
 * Wire types shared with the Android client.
 *
 * Keep this file dependency-free and structural: it is the contract, and the
 * Kotlin side mirrors it by hand. Anything added here needs a matching change
 * in `android/.../net/Protocol.kt`.
 */

export const API_PREFIX = "/api/v1";

/**
 * Wire protocol revision, reported by `/ping`.
 *
 * The app checks it and says so plainly when the bridge is too old or too new,
 * instead of failing in some confusing downstream way. Bump on any breaking
 * change to the shapes below.
 */
export const PROTOCOL = 2;

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

/**
 * The settings half of a session — what the header bar and pickers show.
 *
 * Deliberately not "everything about a session": whether it is running, what it
 * is doing, and how full its context is all live in [SessionStatus], which is
 * pushed when it changes rather than polled.
 */
export interface SessionDetailDto {
	id: string;
	cwd: string;
	/** User-defined display name (`/name`), if set. */
	name?: string;
	/** First user message, truncated — the fallback title. */
	firstMessage: string;
	/** Current model as recorded in the session, or null if never set. */
	model: { provider: string; modelId: string } | null;
	thinkingLevel: string;
	/**
	 * Exact levels the current model accepts. Present only while an agent is
	 * loaded — otherwise the client falls back to the set from `/models`.
	 */
	availableThinkingLevels?: string[];
}

export interface ContextUsageDto {
	tokens: number | null;
	contextWindow: number | null;
	percent: number | null;
}

/* ---------------- items: the view model on the wire ---------------- */

/**
 * An opaque handle for content the server shortened.
 *
 * The client passes it back to `GET /full?ref=` and never parses it. Keeping it
 * opaque is what let the old `{entryId, part, index}` triple — mirrored as an
 * enum on both sides — collapse into one string.
 */
export type Ref = string;

/** Text that may have been cut. `more` is present exactly when it was. */
export interface Text {
	s: string;
	more?: { ref: Ref; bytes: number };
}

/** An image is never inlined: the largest one measured was 361KB of base64. */
export interface Blob {
	ref: Ref;
	mime: string;
	bytes: number;
}

export interface Usage {
	in: number;
	out: number;
	cacheRead: number;
	/** Total cost in dollars, cache reads included. */
	cost: number;
}

/** An edit call, pre-parsed so the client can render red/green lines. */
export interface ToolDiff {
	path?: string;
	hunks: Array<{ old: string; new: string }>;
}

export type NoticeKind = "text" | "compaction" | "branch" | "model" | "thinking" | "named";

/**
 * One row of the conversation.
 *
 * This is the whole client-facing model: a session is a list of these, and a
 * streaming message is simply one that is not finished yet (`pending`). The
 * client holds no second representation for live content.
 *
 * Tool calls are items in their own right rather than nested inside the
 * assistant message that made them. That keeps [ItemPatch] addressable by a
 * single id — no path into a nested array — and moves call/result pairing to the
 * server, which has the whole tree and does not have to guess across page
 * boundaries. Grouping them back under their assistant message is a rendering
 * concern, decided by adjacency.
 */
export type Item =
	| { kind: "user"; id: string; at: string; text: Text; images?: Blob[] }
	| {
			kind: "assistant";
			id: string;
			at: string;
			thinking?: Text;
			text: Text;
			usage?: Usage;
			error?: string;
			/** Set while the message is still streaming. */
			pending?: boolean;
	  }
	| {
			kind: "tool";
			id: string;
			at: string;
			/** The `toolCallId`, when this came from a real tool call. */
			callId?: string;
			name: string;
			/** The one identifying argument — a path, a command. */
			title?: string;
			/** Pretty-printed arguments, one `k: v` per line. */
			args?: Text;
			output: Text;
			isError?: boolean;
			hasImage?: boolean;
			exit?: number;
			diff?: ToolDiff;
			/** Set while the tool is still executing. */
			running?: boolean;
	  }
	| { kind: "notice"; id: string; at: string; note: NoticeKind; arg?: string };

/**
 * A [Text] update where either half may be omitted.
 *
 * `s` absent means "keep the text you already have, just take the handle". That
 * is the normal case at the end of a stream: the client received every delta, so
 * its copy is complete and better than the shortened one on file — but it still
 * needs the `more` handle to offer "show all" for the part that was never
 * streamed.
 */
export interface TextPatch {
	s?: string;
	more?: { ref: Ref; bytes: number };
}

/** Fields a [Push] of kind `patch` may replace. Absent means unchanged. */
export interface ItemPatch {
	text?: TextPatch;
	thinking?: TextPatch;
	output?: TextPatch;
	usage?: Usage;
	error?: string;
	pending?: boolean;
	running?: boolean;
	exit?: number;
	isError?: boolean;
	hasImage?: boolean;
	title?: string;
	args?: Text;
	diff?: ToolDiff;
	images?: Blob[];
}

/** Everything about what a session is *doing*. Pushed on every change. */
export interface SessionStatus {
	running: boolean;
	/** Messages waiting behind the current turn. */
	queued: string[];
	compacting: boolean;
	context?: ContextUsageDto;
}

export interface ItemPageDto {
	/** Oldest-first within the page. */
	items: Item[];
	/** True when older items exist before `oldest`. */
	hasMore: boolean;
	/** Cursor to pass as `before` for the next older page; null when empty. */
	oldest: string | null;
}

/* ---------------- git (read-only) ---------------- */

export interface GitStatusDto {
	branch: string;
	changes: GitChangeDto[];
}

export interface GitChangeDto {
	path: string;
	status: "M" | "A" | "D" | "R" | "C" | "T" | "U";
	added: number;
	deleted: number;
}

export interface GitDiffDto {
	path: string;
	hunks: GitHunkDto[];
}

export interface GitCommitDto {
	hash: string;
	shortHash: string;
	subject: string;
	author: string;
	date: string;
	added: number;
	deleted: number;
}

export interface GitCommitsPageDto {
	commits: GitCommitDto[];
	/** Cursor for the older page (parent of the last commit), or null. */
	nextCursor: string | null;
}

export interface GitCommitDiffDto {
	sha: string;
	shortHash: string;
	subject: string;
	author: string;
	date: string;
	files: GitFileDiffDto[];
}

export interface GitFileDiffDto {
	path: string;
	status: "M" | "A" | "D" | "R" | "C" | "T";
	hunks: GitHunkDto[];
}

export interface GitHunkDto {
	oldStart: number;
	oldCount: number;
	newStart: number;
	newCount: number;
	lines: GitDiffLineDto[];
}

export interface GitDiffLineDto {
	type: "context" | "add" | "remove";
	text: string;
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

/** An image attachment for a prompt, base64-encoded on the wire. */
export interface PromptImageDto {
	type: "image";
	/** base64-encoded image bytes. */
	data: string;
	mimeType: string;
}

/* ---------- WebSocket ---------- */

/** Client → server. */
export type WsCommand =
	| { op: "subscribe"; sessionId: string; sinceSeq?: number }
	| { op: "unsubscribe"; sessionId: string }
	| { op: "ping" };

/**
 * Server → client.
 *
 * Only mutations of the item list travel here, never raw SDK events. That is the
 * point of the whole layer: `live/translate.ts` is the one place that knows what
 * an `AgentSessionEvent` looks like, so the client cannot be broken by the SDK
 * growing a new event kind, and half the old wire — which turned out to be
 * events the client never read — simply has nowhere to go.
 */
export type Push =
	| {
			/**
			 * The full current view. Sent for a fresh subscribe, and again
			 * whenever incremental catch-up is impossible — a replay gap, or the
			 * agent being reloaded because someone else wrote the file. One
			 * resync path instead of a `gap` flag plus a `session_reloaded`
			 * event, each with its own client-side handling.
			 */
			t: "hello";
			sessionId: string;
			seq: number;
			items: Item[];
			hasMore: boolean;
			oldest: string | null;
			detail: SessionDetailDto;
			status: SessionStatus;
	  }
	| { t: "add"; sessionId: string; seq: number; item: Item }
	| {
			t: "patch";
			sessionId: string;
			seq: number;
			id: string;
			/** Concatenate onto one growing field. The streaming path. */
			append?: { f: "text" | "thinking" | "output"; s: string };
			/** Replace named fields. Everything that is not a string append. */
			set?: ItemPatch;
	  }
	| { t: "status"; sessionId: string; seq: number; status: SessionStatus }
	| { t: "unsubscribed"; sessionId: string }
	| { t: "pong" }
	| { t: "error"; sessionId?: string; message: string; code?: string };

export interface ErrorDto {
	error: string;
	/** Machine-readable discriminator, e.g. "session_busy". */
	code?: string;
}
