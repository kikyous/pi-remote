import type { Server } from "node:http";

import { WebSocket, WebSocketServer } from "ws";

import {
	acquire,
	type BufferedPush,
	type EventListener,
	getLoaded,
	setResyncHandler,
	snapshotPoint,
	subscribe,
	unsubscribe,
} from "./agent-pool.ts";
import { idleStatus } from "./commands.ts";
import { isAuthorized } from "./http.ts";
import type { Item, Push, SessionStatus, WsCommand } from "./protocol.ts";
import { getDetail, itemPageOf, requireLocated } from "./store.ts";

/**
 * Live push fan-out.
 *
 * A connection may follow several sessions at once, and several connections may
 * follow the same session — that is what lets a phone and a tablet stay in step
 * without the server arbitrating between them.
 *
 * Only item mutations travel here. `hello` is the single resync path: a fresh
 * subscribe, a cursor that no longer means anything, and an agent reloaded under
 * its subscribers all end up sending one — replacing a `gap` flag and a
 * `session_reloaded` event that the client had to handle separately.
 *
 * A client that *can* be caught up incrementally gets each changed item resent in
 * full rather than a replay of the pushes it missed. See [sendCatchUp].
 */

/** Idle connections are dropped after one missed heartbeat round. */
const HEARTBEAT_MS = 30_000;

/** Items sent in a `hello`. Matches the client's first page. */
const HELLO_ITEMS = 50;

/**
 * How far back a catch-up looks for the items it has to resend.
 *
 * Generous rather than tight: an unresolvable id costs a snapshot, and only the
 * items actually named are sent, so scanning wide is nearly free.
 */
const CATCH_UP_SCAN = 400;

interface Connection {
	socket: WebSocket;
	/** sessionId → the listener registered for it, so we can detach cleanly. */
	following: Map<string, EventListener>;
	alive: boolean;
}

export function attachWebSocket(server: Server, token: string): WebSocketServer {
	const wss = new WebSocketServer({ noServer: true });
	const connections = new Map<WebSocket, Connection>();

	// An agent replaced under its subscribers restarts at seq 0, so every cursor
	// is stale; the only correct move is a fresh snapshot to each follower.
	//
	// A fresh subscribe rather than a bare sendHello: reload swaps the agent
	// under us and the carried listener is already flowing, so a bare hello
	// races the in-flight appends of the new agent — the client would receive
	// the same deltas twice (visible as doubled streaming output). doSubscribe's
	// held/release window withholds exactly the pushes the snapshot covers.
	setResyncHandler((sessionId) => {
		for (const conn of connections.values()) {
			if (conn.following.has(sessionId)) void doSubscribe(conn, sessionId, undefined);
		}
	});

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
				send(socket, { t: "error", message: err instanceof Error ? err.message : String(err) });
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
		send(conn.socket, { t: "error", message: "Message is not valid JSON", code: "bad_json" });
		return;
	}

	switch (command.op) {
		case "ping":
			send(conn.socket, { t: "pong" });
			return;

		case "subscribe":
			await doSubscribe(conn, command.sessionId, command.sinceSeq);
			return;

		case "unsubscribe":
			doUnsubscribe(conn, command.sessionId);
			return;

		default:
			send(conn.socket, { t: "error", message: `Unknown op: ${(command as { op: string }).op}`, code: "bad_op" });
	}
}

async function doSubscribe(conn: Connection, sessionId: string, sinceSeq: number | undefined): Promise<void> {
	if (typeof sessionId !== "string" || sessionId.length === 0) {
		send(conn.socket, { t: "error", message: "subscribe requires a sessionId", code: "bad_session_id" });
		return;
	}

	// Re-subscribing replaces the previous listener rather than stacking one on
	// top of it, so a client that reconnects mid-run gets one copy of everything.
	doUnsubscribe(conn, sessionId, { quiet: true });

	let live: Awaited<ReturnType<typeof acquire>>;
	try {
		live = await acquire(sessionId);
	} catch (err) {
		send(conn.socket, {
			t: "error",
			sessionId,
			message: err instanceof Error ? err.message : String(err),
			code: "acquire_failed",
		});
		return;
	}

	// The socket may have gone away while the agent was starting up.
	if (conn.socket.readyState !== WebSocket.OPEN) return;

	// Attach before building the snapshot, but hold what arrives.
	//
	// Attaching afterwards would lose any push emitted while the snapshot was
	// being assembled. Attaching without holding sends pushes the snapshot is
	// about to contain anyway — and it really happens: a prompt racing the
	// subscribe queues an `add` for the user's message, which the snapshot's flush
	// then delivers *and* includes, so the message appears twice.
	const held: BufferedPush[] = [];
	let flowing = false;
	const listener: EventListener = (buffered: BufferedPush) => {
		if (flowing) send(conn.socket, buffered.push);
		else held.push(buffered);
	};
	const stale = subscribe(live, listener, sinceSeq);
	conn.following.set(sessionId, listener);

	const release = (after: number) => {
		flowing = true;
		for (const buffered of held) if (buffered.seq > after) send(conn.socket, buffered.push);
		held.length = 0;
	};

	if (!stale) {
		// A first subscribe, or a cursor that no longer means anything.
		console.log(`[ws] hello ${sessionId} (seq=${live.seq})`);
		release(await sendHello(conn, sessionId));
		return;
	}

	console.log(`[ws] resumed ${sessionId} (seq=${live.seq}, stale=${stale.size})`);
	release(await sendCatchUp(conn, sessionId, stale));
}

/**
 * Resend the items that changed while the client was away, each in full.
 *
 * `add` is an upsert on the client, so one push per changed item is enough
 * whatever happened to it — a hundred appends and a `set` collapse into its
 * current state. That is the whole reason the ring buffer could go: the cost is
 * bounded by how many items moved, not by how much streaming happened.
 */
async function sendCatchUp(conn: Connection, sessionId: string, stale: Set<string>): Promise<number> {
	if (stale.size === 0) {
		const live = getLoaded(sessionId);
		return live ? snapshotPoint(live).seq : 0;
	}

	let located: Awaited<ReturnType<typeof requireLocated>>;
	try {
		located = await requireLocated(sessionId);
	} catch {
		// The session vanished under us; a snapshot will report it properly.
		return sendHello(conn, sessionId);
	}

	// No `await` from here to the last send: the sequence, the item list and the
	// in-flight tail have to describe one instant. See [sendHello].
	const live = getLoaded(sessionId);
	if (!live) return sendHello(conn, sessionId);
	const point = snapshotPoint(live);
	const items = itemPageOf(located, undefined, CATCH_UP_SCAN).items;

	// A message the client watched stream is held under the `live-N` it was added
	// as, while the stored entry has an id of its own. Resolve to the id our list
	// uses, but send it back stamped with the one the client knows — otherwise it
	// would hold the same message twice, once stale and once fresh.
	//
	// Only a client that received the original `add live-N` can have it stale: a
	// client that subscribed after the message settled got the entry id in a
	// `hello`, whose sequence is already past the settle, so `live-N` is not in its
	// catch-up set.
	const wanted = new Map<string, string>();
	for (const id of stale) wanted.set(live.translator.resolve(id), id);

	// In list order, so the client never inserts a newer item before an older one.
	//
	// Every frame carries the *same* sequence — the snapshot's — because that is
	// what they mean: "this is how these items look as of point.seq". They are a
	// reconstruction, not a replay, so `live.seq` is deliberately not advanced.
	let sent = 0;
	for (const item of [...items, ...(point.tail ? [point.tail] : [])]) {
		const asKnown = wanted.get(item.id);
		if (asKnown === undefined) continue;
		send(conn.socket, { t: "add", sessionId, seq: point.seq, item: asKnown === item.id ? item : { ...item, id: asKnown } });
		sent++;
	}
	// An id that resolves to nothing is one whose item left the active branch, or
	// one minted by a previous incarnation of the agent. Falling back to a snapshot
	// keeps the client from quietly holding something the server no longer has.
	if (sent < wanted.size) {
		console.log(`[ws] catch-up incomplete for ${sessionId} (${sent}/${wanted.size}) — snapshotting`);
		return sendHello(conn, sessionId);
	}

	send(conn.socket, { t: "status", sessionId, seq: point.seq, status: point.status });
	return point.seq;
}

/**
 * Send the whole current view of a session.
 *
 * The in-flight item is appended to the stored ones: a message being streamed
 * right now is not in the session file yet, so a client subscribing mid-run would
 * otherwise see the conversation stop one message short and only catch up when
 * the turn ended.
 */
async function sendHello(conn: Connection, sessionId: string): Promise<number> {
	let located: Awaited<ReturnType<typeof requireLocated>>;
	let detail: Awaited<ReturnType<typeof getDetail>>;
	let idle: SessionStatus | undefined;
	try {
		[located, detail] = await Promise.all([requireLocated(sessionId), getDetail(sessionId)]);
		// A session with no agent loaded still has a context bar to fill, and only
		// the file can answer for it.
		if (!getLoaded(sessionId)) idle = await idleStatus(sessionId, detail.model);
	} catch (err) {
		send(conn.socket, {
			t: "error",
			sessionId,
			message: err instanceof Error ? err.message : String(err),
			code: "hello_failed",
		});
		return 0;
	}

	// From here to the send there is no `await`, on purpose: the flush, the
	// sequence number and the item list have to describe the same instant, or an
	// entry landing in a gap between them would be in neither the snapshot nor the
	// pushes released afterwards.
	const live = getLoaded(sessionId);
	const point = live ? snapshotPoint(live) : undefined;
	const page = itemPageOf(located, undefined, HELLO_ITEMS);

	const items: Item[] = point?.tail ? [...page.items, point.tail] : page.items;
	send(conn.socket, {
		t: "hello",
		sessionId,
		seq: point?.seq ?? 0,
		items,
		hasMore: page.hasMore,
		oldest: page.oldest,
		detail,
		status: point?.status ?? idle ?? { running: false, queued: [], compacting: false },
	});
	return point?.seq ?? 0;
}

function doUnsubscribe(conn: Connection, sessionId: string, options?: { quiet?: boolean }): void {
	const listener = conn.following.get(sessionId);
	if (!listener) return;
	conn.following.delete(sessionId);

	// The agent may already be gone (idle-disposed); detaching is then moot.
	const live = getLoaded(sessionId);
	if (live) unsubscribe(live, listener);

	if (!options?.quiet) send(conn.socket, { t: "unsubscribed", sessionId });
}

function detachAll(conn: Connection): void {
	for (const sessionId of [...conn.following.keys()]) {
		doUnsubscribe(conn, sessionId, { quiet: true });
	}
}

function send(socket: WebSocket, push: Push): void {
	if (socket.readyState !== WebSocket.OPEN) return;
	socket.send(JSON.stringify(push));
}
