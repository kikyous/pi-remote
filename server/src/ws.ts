import type { Server } from "node:http";

import { WebSocket, WebSocketServer } from "ws";

import {
	acquire,
	type BufferedEvent,
	type EventListener,
	getLoaded,
	subscribe,
	unsubscribe,
} from "./agent-pool.ts";
import { isAuthorized } from "./http.ts";
import type { WsCommand, WsMessage } from "./protocol.ts";

/**
 * Live event fan-out.
 *
 * A connection may follow several sessions at once, and several connections may
 * follow the same session — that is what lets a phone and a tablet stay in step
 * without the server arbitrating between them.
 */

/** Idle connections are dropped after one missed heartbeat round. */
const HEARTBEAT_MS = 30_000;

interface Connection {
	socket: WebSocket;
	/** sessionId → the listener registered for it, so we can detach cleanly. */
	following: Map<string, EventListener>;
	alive: boolean;
}

export function attachWebSocket(server: Server, token: string): WebSocketServer {
	const wss = new WebSocketServer({ noServer: true });
	const connections = new Map<WebSocket, Connection>();

	server.on("upgrade", (req, socket, head) => {
		const url = new URL(req.url ?? "/", "http://localhost");
		if (url.pathname !== "/ws") {
			socket.destroy();
			return;
		}
		if (!isAuthorized(req, url, token)) {
			console.warn("[ws] rejected upgrade: bad token");
			socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
			socket.destroy();
			return;
		}
		wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
	});

	wss.on("connection", (socket: WebSocket) => {
		const conn: Connection = { socket, following: new Map(), alive: true };
		connections.set(socket, conn);
		console.log(`[ws] client connected (${connections.size} total)`);

		socket.on("message", (raw) => {
			void handleCommand(conn, raw.toString()).catch((err) => {
				send(socket, { op: "error", message: err instanceof Error ? err.message : String(err) });
			});
		});

		socket.on("pong", () => {
			conn.alive = true;
		});

		const cleanUp = () => {
			detachAll(conn);
			connections.delete(socket);
			console.log(`[ws] client disconnected (${connections.size} left)`);
		};
		socket.on("close", cleanUp);
		socket.on("error", cleanUp);
	});

	const heartbeat = setInterval(() => {
		for (const conn of connections.values()) {
			if (!conn.alive) {
				// Missed the previous round: half-open, e.g. a phone that left
				// Wi-Fi without the TCP connection ever closing.
				conn.socket.terminate();
				continue;
			}
			conn.alive = false;
			conn.socket.ping();
		}
	}, HEARTBEAT_MS);
	heartbeat.unref();

	wss.on("close", () => clearInterval(heartbeat));
	return wss;
}

async function handleCommand(conn: Connection, raw: string): Promise<void> {
	let command: WsCommand;
	try {
		command = JSON.parse(raw) as WsCommand;
	} catch {
		send(conn.socket, { op: "error", message: "Message is not valid JSON", code: "bad_json" });
		return;
	}

	switch (command.op) {
		case "ping":
			send(conn.socket, { op: "pong" });
			return;

		case "subscribe":
			await doSubscribe(conn, command.sessionId, command.sinceSeq);
			return;

		case "unsubscribe":
			doUnsubscribe(conn, command.sessionId);
			return;

		default:
			send(conn.socket, { op: "error", message: `Unknown op: ${(command as { op: string }).op}`, code: "bad_op" });
	}
}

async function doSubscribe(conn: Connection, sessionId: string, sinceSeq: number | undefined): Promise<void> {
	if (typeof sessionId !== "string" || sessionId.length === 0) {
		send(conn.socket, { op: "error", message: "subscribe requires a sessionId", code: "bad_session_id" });
		return;
	}

	// Re-subscribing replaces the previous listener rather than stacking one on
	// top of it, so a client that reconnects mid-run gets one copy of events.
	doUnsubscribe(conn, sessionId, { quiet: true });

	let live: Awaited<ReturnType<typeof acquire>>;
	try {
		live = await acquire(sessionId);
	} catch (err) {
		send(conn.socket, {
			op: "error",
			sessionId,
			message: err instanceof Error ? err.message : String(err),
			code: "acquire_failed",
		});
		return;
	}

	// The socket may have gone away while the agent was starting up.
	if (conn.socket.readyState !== WebSocket.OPEN) return;

	const listener: EventListener = (buffered: BufferedEvent) => {
		send(conn.socket, {
			op: "event",
			sessionId,
			seq: buffered.seq,
			...(buffered.entryId ? { entryId: buffered.entryId } : {}),
			event: buffered.event,
		});
	};

	const { replay, gap } = subscribe(live, listener, sinceSeq);
	conn.following.set(sessionId, listener);
	console.log(`[ws] subscribed ${sessionId} (seq=${live.seq}, replay=${replay.length}, gap=${gap})`);

	// Report position before replaying, so the client can act on `gap`
	// immediately instead of after a burst of events it will discard.
	send(conn.socket, { op: "subscribed", sessionId, seq: live.seq, gap, running: live.session.isStreaming });
	for (const buffered of replay) listener(buffered);
}

function doUnsubscribe(conn: Connection, sessionId: string, options?: { quiet?: boolean }): void {
	const listener = conn.following.get(sessionId);
	if (!listener) return;
	conn.following.delete(sessionId);

	// The agent may already be gone (idle-disposed); detaching is then moot.
	const live = getLoaded(sessionId);
	if (live) unsubscribe(live, listener);

	if (!options?.quiet) send(conn.socket, { op: "unsubscribed", sessionId });
}

function detachAll(conn: Connection): void {
	for (const sessionId of [...conn.following.keys()]) {
		doUnsubscribe(conn, sessionId, { quiet: true });
	}
}

function send(socket: WebSocket, message: WsMessage): void {
	if (socket.readyState !== WebSocket.OPEN) return;
	socket.send(JSON.stringify(message));
}
