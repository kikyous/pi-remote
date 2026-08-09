import { createServer } from "node:http";

import { createSession, createWorkspace, disposeAll, isRunning } from "./agent-pool.ts";
import { deleteSession, deleteWorkspace } from "./delete.ts";
import {
	abortSession,
	listModels,
	loadedSessionState,
	sendPrompt,
	sessionContextUsage,
	type SessionPatch,
	updateSession,
} from "./commands.ts";
import { lanAddresses, parseArgs } from "./config.ts";
import { gitCommitDiff, gitCommits, gitDiff, gitStatus } from "./git.ts";
import { HttpError, Router } from "./http.ts";
import { API_PREFIX, type PromptImageDto } from "./protocol.ts";
import type { FullPart } from "./slim.ts";
import { getEntryPage, getFullPart, getSessionDetail, listProjects, listSessions, setRunningProbe } from "./store.ts";
import { attachWebSocket } from "./ws.ts";

const VERSION = "0.1.0";

const DEFAULT_PAGE_LIMIT = 50;
const MAX_PAGE_LIMIT = 200;
const FULL_PARTS = new Set<FullPart>(["text", "thinking", "image", "arguments", "output"]);

function main(): void {
	let config;
	try {
		config = parseArgs(process.argv.slice(2));
	} catch (err) {
		console.error(err instanceof Error ? err.message : String(err));
		process.exit(1);
	}

	// Lets the read-only layer report live state without importing the pool.
	setRunningProbe(isRunning);

	const router = new Router();

	/** Cheap reachability + auth check for the client's connection setup screen. */
	router.get(`${API_PREFIX}/ping`, () => ({ ok: true, version: VERSION }));

	router.get(`${API_PREFIX}/projects`, () => listProjects());

	router.get(`${API_PREFIX}/sessions`, (ctx) => {
		const cwd = decodeCwd(ctx.query.get("cwd"));
		return listSessions(cwd);
	});

	router.get(`${API_PREFIX}/sessions/:id`, async (ctx) => {
		const detail = await getSessionDetail(ctx.params.id!);
		// A loaded agent holds the authoritative model and thinking level — the
		// file may lag it, or not exist yet for a session created moments ago.
		const live = loadedSessionState(ctx.params.id!);
		const context = await sessionContextUsage(ctx.params.id!, detail.model);
		return { ...detail, ...(live ?? {}), context };
	});

	router.get(`${API_PREFIX}/sessions/:id/entries`, (ctx) => {
		const before = ctx.query.get("before") ?? undefined;
		return getEntryPage(ctx.params.id!, before, parseLimit(ctx.query.get("limit")));
	});

	router.get(`${API_PREFIX}/sessions/:id/entries/:entryId/full`, (ctx) => {
		const part = parsePart(ctx.query.get("part"));
		const rawIndex = ctx.query.get("index");
		const index = rawIndex === null ? undefined : Number(rawIndex);
		if (index !== undefined && !Number.isInteger(index)) {
			throw new HttpError(400, "index must be an integer", "bad_index");
		}
		return getFullPart(ctx.params.id!, ctx.params.entryId!, part, index);
	});

	router.get(`${API_PREFIX}/models`, () => listModels());

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
		return createSession(cwd, {
			...(typeof body.provider === "string" ? { provider: body.provider } : {}),
			...(typeof body.modelId === "string" ? { modelId: body.modelId } : {}),
			...(typeof body.thinkingLevel === "string" ? { thinkingLevel: body.thinkingLevel } : {}),
		}).then((id) => ({ id }));
	});

	// One-tap default workspace: `~/pi-cwd-YYYYMMDD` on the server. The path is
	// derived entirely server-side, so the client never sends a directory.
	router.post(`${API_PREFIX}/workspaces`, () => createWorkspace());

	// Swipe-to-delete: sessions, and whole workspaces (all their sessions).
	router.delete(`${API_PREFIX}/sessions/:id`, (ctx) => deleteSession(ctx.params.id!));
	router.delete(`${API_PREFIX}/workspaces`, (ctx) => deleteWorkspace(decodeCwd(ctx.query.get("cwd"))));

	router.post(`${API_PREFIX}/sessions/:id/prompt`, (ctx) => {
		const body = asRecord(ctx.body);
		const behavior = body.streamingBehavior;
		if (behavior !== undefined && behavior !== "steer" && behavior !== "followUp") {
			throw new HttpError(400, "streamingBehavior must be 'steer' or 'followUp'", "bad_streaming_behavior");
		}
		const images = parseImages(body.images);
		return sendPrompt(ctx.params.id!, body.message as string, behavior, images);
	});

	router.post(`${API_PREFIX}/sessions/:id/abort`, (ctx) => abortSession(ctx.params.id!));

	router.patch(`${API_PREFIX}/sessions/:id`, (ctx) => {
		const body = asRecord(ctx.body);
		const patch: SessionPatch = {};
		if (typeof body.provider === "string") patch.provider = body.provider;
		if (typeof body.modelId === "string") patch.modelId = body.modelId;
		if (typeof body.thinkingLevel === "string") patch.thinkingLevel = body.thinkingLevel;
		if (typeof body.name === "string") patch.name = body.name;
		return updateSession(ctx.params.id!, patch);
	});

	const server = createServer(router.listener(config.token));
	attachWebSocket(server, config.token);

	server.listen(config.port, config.host, () => {
		printBanner(config.port, config.host, config.token);
	});

	server.on("error", (err) => {
		console.error(`Failed to listen on ${config.host}:${config.port}:`, err.message);
		process.exit(1);
	});

	const shutdown = (signal: string) => {
		console.log(`\n${signal} received, shutting down.`);
		// Dispose agents first: they hold session files open and have pending
		// writes that should land before the process goes away.
		void disposeAll().finally(() => server.close(() => process.exit(0)));
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

function parsePart(raw: string | null): FullPart {
	if (raw === null) throw new HttpError(400, "Missing part parameter", "missing_part");
	if (!FULL_PARTS.has(raw as FullPart)) {
		throw new HttpError(400, `part must be one of ${[...FULL_PARTS].join(", ")}`, "bad_part");
	}
	return raw as FullPart;
}

function printBanner(port: number, host: string, token: string): void {
	const shown = host === "0.0.0.0" || host === "::" ? (lanAddresses()[0] ?? "127.0.0.1") : host;
	console.log(`pi-remote-bridge ${VERSION}`);
	console.log(`  URL:   http://${shown}:${port}`);
	console.log(`  Token: ${token}`);
	if (host === "0.0.0.0" || host === "::") {
		const others = lanAddresses().slice(1);
		if (others.length > 0) {
			console.log(`  Also reachable at: ${others.map((a) => `http://${a}:${port}`).join(", ")}`);
		}
		console.log("  Listening on all interfaces — only use this on a trusted network.");
	}
}

main();
