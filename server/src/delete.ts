import { unlink } from "node:fs/promises";

import { destroy } from "./agent-pool.ts";
import { HttpError } from "./http.ts";
import { forgetModel } from "./sessions/model.ts";
import { forget } from "./sessions/scan.ts";
import { childCwds, dropPendingSession, requireLocated, sessionsByCwd } from "./store.ts";

/**
 * Delete one session: its JSONL file (or pending placeholder) and any loaded
 * agent. The fork rule: a session that has children must not be deleted, or
 * its forks would lose their parent chain. Sessions without children are
 * always safe to remove — pi treats a missing parent as a root.
 */
export async function deleteSession(sessionId: string): Promise<{ deleted: number }> {
	const info = await requireLocated(sessionId);
	if ((await childCwds(info.path)).length > 0) {
		throw new HttpError(409, "Session has forked children; delete the forks first", "has_children");
	}
	await removeSession(info.id, info.path);
	return { deleted: 1 };
}

/**
 * Delete every session under a workspace directory (`cwd`). The directory
 * itself stays on disk — sessions are what make a workspace appear in the
 * list, so removing them makes it disappear.
 */
export async function deleteWorkspace(cwd: string): Promise<{ deleted: number }> {
	const inWorkspace = await sessionsByCwd(cwd);

	// Forks inside the same workspace die with their parent, so only a fork
	// living elsewhere would be orphaned. Refuse rather than break the chain.
	for (const info of inWorkspace) {
		const forkedElsewhere = (await childCwds(info.path)).some((childCwd) => childCwd !== cwd);
		if (forkedElsewhere) {
			throw new HttpError(409, "A forked session lives outside this workspace", "has_children");
		}
	}

	let deleted = 0;
	for (const info of inWorkspace) {
		await removeSession(info.id, info.path);
		deleted++;
	}
	return { deleted };
}

async function removeSession(id: string, path: string): Promise<void> {
	// Dispose the loaded agent first: it holds the file open and could write
	// the session back after we unlink it.
	await destroy(id);
	dropPendingSession(id);
	await unlink(path).catch(() => {
		// Pending sessions have no file yet; ENOENT is expected, not an error.
	});
	// A deletion is the one change `(mtime, size)` cannot express — the file is
	// simply gone — so both caches are told explicitly.
	forget(id);
	forgetModel(path);
}
