import { createServer } from "node:http";

import { backend, type SessionPatch, setBackend } from "./backend.ts";
import { lanAddresses, parseArgs, type ServerConfig } from "./config.ts";
import { isDebug, setDebug } from "./debug.ts";
import { buildConnectPayload, renderConnectQr } from "./qr.ts";
import { gitCommitDiff, gitCommits, gitDiff, gitStatus } from "./git.ts";
import { HttpError, Router } from "./http.ts";
import { API_PREFIX, PROTOCOL, type PromptImageDto } from "./protocol.ts";
import { attachWebSocket } from "./ws.ts";

const VERSION = "0.4.0";

const DEFAULT_PAGE_LIMIT = 50;
const MAX_PAGE_LIMIT = 200;

/**
 * Load the pi backend.
 *
 * Imported on demand rather than at the top: the pi SDK is several megabytes of
 * agent runtime, and the bridge only pays for it when it actually starts.
 */
async function selectBackend(): Promise<void> {
	const { createPiBackend } = await import("./backends/pi/index.ts");
	setBackend(createPiBackend());
}

async function main(): Promise<void> {
	let config: ServerConfig;
	try {
		config = parseArgs(process.argv.slice(2));
	} catch (err) {
		console.error(err instanceof Error ? err.message : String(err));
		process.exit(1);
	}

	setDebug(config.debug);
	// Everything below this line is written against `AgentBackend`; which agent is
	// actually behind it is decided exactly here.
	await selectBackend();

	const router = new Router();

	// Cheap reachability + auth check, and the version handshake: the app compares
	// `protocol` and says plainly that the bridge needs upgrading rather than
	// failing somewhere downstream.
	router.get(`${API_PREFIX}/ping`, () => ({ ok: true, version: VERSION, protocol: PROTOCOL }));

	router.get(`${API_PREFIX}/projects`, () => backend().listProjects());

	router.get(`${API_PREFIX}/sessions`, (ctx) => {
		const cwd = decodeCwd(ctx.query.get("cwd"));
		return backend().listSessions(cwd);
	});

	// There is no `GET /sessions/:id`: the settings arrive in the WebSocket's
	// `hello`, and what a session is *doing* is pushed as status. One round trip
	// opens a session instead of two, and nothing polls.
	router.get(`${API_PREFIX}/sessions/:id/items`, (ctx) => {
		const before = ctx.query.get("before") ?? undefined;
		return backend().getItemPage(ctx.params.id!, before, parseLimit(ctx.query.get("limit")));
	});

	// One endpoint for every kind of shortened content, addressed by the opaque
	// handle the item carried.
	router.get(`${API_PREFIX}/sessions/:id/full`, (ctx) => {
		const ref = ctx.query.get("ref");
		if (!ref) throw new HttpError(400, "Missing ref parameter", "missing_ref");
		return backend().getFullByRef(ctx.params.id!, ref);
	});

	// What this session has spent: messages, tokens, dollars. Read-only and
	// agent-free, so opening the info sheet costs no more than a page of history.
	router.get(`${API_PREFIX}/sessions/:id/stats`, (ctx) => {
		const agent = backend();
		if (!agent.stats) {
			throw new HttpError(501, "This agent does not report session stats", "stats_unsupported");
		}
		return agent.stats(ctx.params.id!);
	});

	router.get(`${API_PREFIX}/models`, () => backend().listModels());

	// Read-only git queries for the repo a session lives in.
	router.get(`${API_PREFIX}/git/status`, (ctx) => gitStatus(decodeCwd(ctx.query.get("cwd"))));
	router.get(`${API_PREFIX}/git/diff`, (ctx) => {
		const file = ctx.query.get("file");
		if (!file) throw new HttpError(400, "Missing file parameter", "missing_file");
		return gitDiff(decodeCwd(ctx.query.get("cwd")), decodeBase64Url(file));
	});
	router.get(`${API_PREFIX}/git/commits`, (ctx) => {
		const before = ctx.query.get("before");
		return gitCommits(decodeCwd(ctx.query.get("cwd")), parseLimit(ctx.query.get("limit")), before ?? undefined);
	});
	router.get(`${API_PREFIX}/git/commit`, (ctx) => {
		const sha = ctx.query.get("sha");
		if (!sha) throw new HttpError(400, "Missing sha parameter", "missing_sha");
		return gitCommitDiff(decodeCwd(ctx.query.get("cwd")), sha);
	});

	router.post(`${API_PREFIX}/sessions`, (ctx) => {
		const body = asRecord(ctx.body);
		const cwd = body.cwd;
		if (typeof cwd !== "string" || !cwd.startsWith("/")) {
			throw new HttpError(400, "cwd must be an absolute path", "bad_cwd");
		}
		return backend().createSession(cwd, {
			...(typeof body.provider === "string" ? { provider: body.provider } : {}),
			...(typeof body.modelId === "string" ? { modelId: body.modelId } : {}),
			...(typeof body.thinkingLevel === "string" ? { thinkingLevel: body.thinkingLevel } : {}),
		}).then((id) => ({ id }));
	});

	// One-tap default workspace: `~/pi-cwd-YYYYMMDD` on the server. The path is
	// derived entirely server-side, so the client never sends a directory.
	router.post(`${API_PREFIX}/workspaces`, () => backend().createWorkspace());

	// Swipe-to-delete: sessions, and whole workspaces (all their sessions).
	router.delete(`${API_PREFIX}/sessions/:id`, (ctx) => backend().deleteSession(ctx.params.id!));
	router.delete(`${API_PREFIX}/workspaces`, (ctx) => backend().deleteWorkspace(decodeCwd(ctx.query.get("cwd"))));

	router.post(`${API_PREFIX}/sessions/:id/prompt`, (ctx) => {
		const body = asRecord(ctx.body);
		const behavior = body.streamingBehavior;
		if (behavior !== undefined && behavior !== "steer" && behavior !== "followUp") {
			throw new HttpError(400, "streamingBehavior must be 'steer' or 'followUp'", "bad_streaming_behavior");
		}
		const images = parseImages(body.images);
		return backend().prompt(ctx.params.id!, body.message as string, behavior, images);
	});

	router.post(`${API_PREFIX}/sessions/:id/abort`, (ctx) => backend().abort(ctx.params.id!));

	// AI-generated title from the conversation; persisted as the session name.
	// Answers with the new detail, so the client does not follow up with a read.
	router.post(`${API_PREFIX}/sessions/:id/title`, async (ctx) => {
		await backend().generateTitle(ctx.params.id!);
		return backend().getDetail(ctx.params.id!);
	});

	// Summarize the conversation into a compaction entry. Slow — the model reads
	// the whole branch — so the app gives this call a timeout of its own; the
	// `compacting` flag on the status push is what the screen shows meanwhile.
	router.post(`${API_PREFIX}/sessions/:id/compact`, (ctx) => {
		const agent = backend();
		if (!agent.compact) {
			throw new HttpError(501, "This agent cannot compact context", "compact_unsupported");
		}
		return agent.compact(ctx.params.id!);
	});

	router.patch(`${API_PREFIX}/sessions/:id`, async (ctx) => {
		const body = asRecord(ctx.body);
		const patch: SessionPatch = {};
		if (typeof body.provider === "string") patch.provider = body.provider;
		if (typeof body.modelId === "string") patch.modelId = body.modelId;
		if (typeof body.thinkingLevel === "string") patch.thinkingLevel = body.thinkingLevel;
		if (typeof body.name === "string") patch.name = body.name;
		// Switching model can clamp the thinking level, so the answer is the whole
		// new detail rather than a list of touched field names — one round trip.
		await backend().updateSession(ctx.params.id!, patch);
		return backend().getDetail(ctx.params.id!);
	});

	const server = createServer(router.listener(config.token));
	attachWebSocket(server, config.token);

	server.listen(config.port, config.host, () => {
		printBanner(config);
	});

	server.on("error", (err) => {
		console.error(`Failed to listen on ${config.host}:${config.port}:`, err.message);
		process.exit(1);
	});

	const shutdown = (signal: string) => {
		console.log(`\n${signal} received, shutting down.`);
		// Dispose agents first: they hold session files open and have pending
		// writes that should land before the process goes away.
		void backend().dispose().finally(() => server.close(() => process.exit(0)));
		// Do not let a hung connection block exit.
		setTimeout(() => process.exit(0), 5_000).unref();
	};
	process.on("SIGINT", () => shutdown("SIGINT"));
	process.on("SIGTERM", () => shutdown("SIGTERM"));
}

/**
 * `cwd` travels as base64url so absolute paths with slashes, spaces, or
 * non-ASCII segments survive the query string unambiguously.
 */
export function decodeCwd(raw: string | null): string {
	const decoded = decodeBase64Url(raw);
	if (!decoded.startsWith("/")) throw new HttpError(400, "cwd must be an absolute path", "bad_cwd");
	return decoded;
}

/** Base64url decode without the absolute-path requirement (git file paths). */
export function decodeBase64Url(raw: string | null): string {
	if (!raw) throw new HttpError(400, "Missing cwd parameter", "missing_cwd");
	let decoded: string;
	try {
		decoded = Buffer.from(raw, "base64url").toString("utf8");
	} catch {
		throw new HttpError(400, "cwd is not valid base64url", "bad_cwd");
	}
	return decoded;
}

function asRecord(body: unknown): Record<string, unknown> {
	if (typeof body !== "object" || body === null || Array.isArray(body)) {
		throw new HttpError(400, "Body must be a JSON object", "bad_body");
	}
	return body as Record<string, unknown>;
}

/**
 * Accept an optional `images` array of `{type:"image", data, mimeType}`.
 * Rejects anything that does not look like a base64 image so a malformed
 * client payload cannot reach the model API.
 */
function parseImages(raw: unknown): PromptImageDto[] | undefined {
	if (raw === undefined) return undefined;
	if (!Array.isArray(raw)) throw new HttpError(400, "images must be an array", "bad_images");
	const images: PromptImageDto[] = [];
	for (const item of raw) {
		if (typeof item !== "object" || item === null) throw new HttpError(400, "image must be an object", "bad_images");
		const rec = item as Record<string, unknown>;
		if (rec.type !== "image" || typeof rec.data !== "string" || typeof rec.mimeType !== "string") {
			throw new HttpError(400, "image needs type/data/mimeType", "bad_images");
		}
		images.push({ type: "image", data: rec.data, mimeType: rec.mimeType });
	}
	return images;
}

function parseLimit(raw: string | null): number {
	if (raw === null) return DEFAULT_PAGE_LIMIT;
	const value = Number(raw);
	if (!Number.isInteger(value) || value < 1) {
		throw new HttpError(400, "limit must be a positive integer", "bad_limit");
	}
	return Math.min(value, MAX_PAGE_LIMIT);
}

function printBanner(config: ServerConfig): void {
	const { port, host, token } = config;
	const shown = host === "0.0.0.0" || host === "::" ? (lanAddresses()[0] ?? "127.0.0.1") : host;
	const url = `http://${shown}:${port}`;
	console.log(`pi-remote-bridge ${VERSION}`);
	console.log(`  URL:   ${url}`);
	console.log(`  Token: ${token}`);
	console.log(`  Agent: pi (in-process SDK)`);
	if (host === "0.0.0.0" || host === "::") {
		const others = lanAddresses().slice(1);
		if (others.length > 0) {
			console.log(`  Also reachable at: ${others.map((a) => `http://${a}:${port}`).join(", ")}`);
		}
		console.log("  Listening on all interfaces — only use this on a trusted network.");
	}
	if (isDebug()) console.log("  Debug: logging every request and WebSocket message.");
	// QR pairing: the phone scans this code from the PC screen and the app
	// fills address + token by itself, no typing required.
	void renderConnectQr(buildConnectPayload(url, token)).then((qr) => {
		console.log("\n  Scan with Pi Remote (app → Scan to connect):");
		console.log(qr);
	});
}

void main();
