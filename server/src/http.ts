import { timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

import type { ErrorDto } from "./protocol.ts";

export interface RequestContext {
	req: IncomingMessage;
	res: ServerResponse;
	/** Path parameters captured from the route pattern, e.g. `:id`. */
	params: Record<string, string>;
	query: URLSearchParams;
	/** Parsed JSON body; `{}` for requests without one. */
	body: unknown;
}

export type Handler = (ctx: RequestContext) => Promise<unknown> | unknown;

/**
 * Thrown by handlers to produce a non-200 JSON response.
 *
 * Fields are assigned explicitly rather than declared as constructor parameter
 * properties: Node's `--experimental-strip-types` only erases types, it does
 * not transform code, so parameter properties would break `node --test`.
 */
export class HttpError extends Error {
	readonly status: number;
	readonly code: string | undefined;

	constructor(status: number, message: string, code?: string) {
		super(message);
		this.name = "HttpError";
		this.status = status;
		this.code = code;
	}
}

interface Route {
	method: string;
	/** Pattern split on "/", where segments starting with ":" are parameters. */
	segments: string[];
	handler: Handler;
}

const MAX_BODY_BYTES = 4 * 1024 * 1024;

export class Router {
	private readonly routes: Route[] = [];

	get(pattern: string, handler: Handler): void {
		this.add("GET", pattern, handler);
	}
	post(pattern: string, handler: Handler): void {
		this.add("POST", pattern, handler);
	}
	patch(pattern: string, handler: Handler): void {
		this.add("PATCH", pattern, handler);
	}
	delete(pattern: string, handler: Handler): void {
		this.add("DELETE", pattern, handler);
	}

	private add(method: string, pattern: string, handler: Handler): void {
		this.routes.push({ method, segments: splitPath(pattern), handler });
	}

	private match(method: string, path: string): { route: Route; params: Record<string, string> } | undefined {
		const parts = splitPath(path);
		for (const route of this.routes) {
			if (route.method !== method) continue;
			if (route.segments.length !== parts.length) continue;
			const params: Record<string, string> = {};
			let ok = true;
			for (let i = 0; i < route.segments.length; i++) {
				const seg = route.segments[i]!;
				const part = parts[i]!;
				if (seg.startsWith(":")) {
					params[seg.slice(1)] = decodeURIComponent(part);
				} else if (seg !== part) {
					ok = false;
					break;
				}
			}
			if (ok) return { route, params };
		}
		return undefined;
	}

	/** Node request listener. `token` gates every route. */
	listener(token: string) {
		return (req: IncomingMessage, res: ServerResponse): void => {
			void this.handle(req, res, token).catch((err) => {
				// Last-resort guard: handle() already converts handler errors.
				if (!res.headersSent) sendError(res, 500, String(err));
			});
		};
	}

	private async handle(req: IncomingMessage, res: ServerResponse, token: string): Promise<void> {
		const url = new URL(req.url ?? "/", "http://localhost");

		if (req.method === "OPTIONS") {
			res.writeHead(204).end();
			return;
		}

		if (!isAuthorized(req, url, token)) {
			sendError(res, 401, "Missing or invalid token", "unauthorized");
			return;
		}

		const matched = this.match(req.method ?? "GET", url.pathname);
		if (!matched) {
			sendError(res, 404, `No route for ${req.method} ${url.pathname}`, "not_found");
			return;
		}

		try {
			const body = await readJsonBody(req);
			const result = await matched.route.handler({
				req,
				res,
				params: matched.params,
				query: url.searchParams,
				body,
			});
			// A handler that wrote the response itself returns undefined.
			if (res.writableEnded) return;
			sendJson(res, 200, result ?? {});
		} catch (err) {
			if (err instanceof HttpError) {
				sendError(res, err.status, err.message, err.code);
			} else {
				console.error(`[${req.method} ${url.pathname}]`, err);
				sendError(res, 500, err instanceof Error ? err.message : String(err));
			}
		}
	}
}

function splitPath(path: string): string[] {
	return path.split("/").filter((s) => s.length > 0);
}

/**
 * Accept the token from the Authorization header (REST) or the query string
 * (WebSocket upgrade, which cannot set headers from the browser/OkHttp side
 * without extra plumbing).
 */
export function isAuthorized(req: IncomingMessage, url: URL, token: string): boolean {
	const header = req.headers.authorization;
	const presented = header?.startsWith("Bearer ") ? header.slice(7).trim() : (url.searchParams.get("token") ?? "");
	return constantTimeEquals(presented, token);
}

function constantTimeEquals(a: string, b: string): boolean {
	const bufA = Buffer.from(a);
	const bufB = Buffer.from(b);
	// timingSafeEqual throws on length mismatch, which itself leaks length —
	// acceptable here, and far cheaper than the alternative on a LAN service.
	if (bufA.length !== bufB.length) return false;
	return timingSafeEqual(bufA, bufB);
}

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
	if (req.method === "GET" || req.method === "HEAD") return {};
	const chunks: Buffer[] = [];
	let size = 0;
	for await (const chunk of req) {
		size += chunk.length;
		if (size > MAX_BODY_BYTES) throw new HttpError(413, "Request body too large", "body_too_large");
		chunks.push(chunk as Buffer);
	}
	if (size === 0) return {};
	try {
		return JSON.parse(Buffer.concat(chunks).toString("utf8"));
	} catch {
		throw new HttpError(400, "Body is not valid JSON", "bad_json");
	}
}

export function sendJson(res: ServerResponse, status: number, payload: unknown): void {
	const text = JSON.stringify(payload);
	res.writeHead(status, {
		"content-type": "application/json; charset=utf-8",
		"content-length": Buffer.byteLength(text),
	});
	res.end(text);
}

export function sendError(res: ServerResponse, status: number, message: string, code?: string): void {
	const payload: ErrorDto = code ? { error: message, code } : { error: message };
	sendJson(res, status, payload);
}
