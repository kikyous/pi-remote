import { existsSync, readFileSync, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { resolve, sep } from "node:path";

import { HttpError } from "./http.ts";
import type { GitChangeDto, GitDiffDto, GitHunkDto, GitStatusDto } from "./protocol.ts";

/**
 * Read-only git queries for the repository a session lives in.
 *
 * Only status and diff are exposed, both read-only, so the working tree is
 * never touched. Paths are still validated to stay inside the repo.
 */

function runGit(cwd: string, args: string[]): Promise<string> {
	return new Promise((resolve, reject) => {
		const child = spawn("git", args, { cwd });
		let out = "";
		let err = "";
		child.stdout.on("data", (chunk: Buffer) => (out += chunk));
		child.stderr.on("data", (chunk: Buffer) => (err += chunk));
		child.on("error", (e: Error) => reject(new HttpError(500, `git: ${e.message}`, "git_error")));
		child.on("close", (code) => {
			if (code === 0) resolve(out);
			else reject(new HttpError(500, `git ${args[0]} failed: ${err.trim() || code}`, "git_error"));
		});
	});
}

export async function gitStatus(cwd: string): Promise<GitStatusDto> {
	const [branchRaw, porcelain, numstatRaw] = await Promise.all([
		runGit(cwd, ["branch", "--show-current"]),
		runGit(cwd, ["status", "--porcelain=v1", "-z"]),
		// Both the staged and the unstaged diff carry line counts.
		runGit(cwd, ["diff", "--numstat"])
			.then((a) => a + "\n" + runGit(cwd, ["diff", "--cached", "--numstat"]))
			.catch(() => ""),
	]);

	const changes = new Map<string, GitChangeDto>();

	// -z records are NUL-separated "XY path"; renames are two records (old, new).
	const records = porcelain.split("\0");
	for (let i = 0; i < records.length; i++) {
		const record = records[i]!;
		if (record.length < 4) continue; // "XY " + at least one path char
		const xy = record.slice(0, 2);
		let path = record.slice(3);
		if ((xy[0] === "R" || xy[0] === "C") && i + 1 < records.length) {
			path = records[++i]!;
		}
		changes.set(path, { path, status: statusLetter(xy), added: 0, deleted: 0 });
	}

	for (const line of numstatRaw.split("\n")) {
		if (!line) continue;
		const [adds, dels, ...rest] = line.split("\t");
		if (rest.length === 0) continue;
		let path = rest.join("\t");
		const arrow = path.lastIndexOf(" => ");
		if (arrow !== -1) path = path.slice(arrow + 4); // renames print "old => new"
		const entry = changes.get(path);
		if (entry) {
			entry.added = Number(adds) || 0;
			entry.deleted = Number(dels) || 0;
		}
	}

	// Untracked files have no diff stat; count their lines directly.
	for (const change of changes.values()) {
		if (change.status === "U") {
			const file = resolve(cwd, change.path);
			if (existsSync(file) && statSync(file).isFile()) {
				change.added = countLines(file);
			}
		}
	}

	return { branch: branchRaw.trim(), changes: [...changes.values()] };
}

export async function gitDiff(cwd: string, path: string): Promise<GitDiffDto> {
	const file = validatePath(cwd, path);

	// Untracked files have no baseline: show the whole file as additions.
	if (!(await isTracked(cwd, path))) {
		const text = existsSync(file) ? readFileSync(file, "utf8") : "";
		const lines = text === "" ? [] : text.split("\n");
		return {
			path,
			hunks: [
				{
					oldStart: 0,
					oldCount: 0,
					newStart: 1,
					newCount: lines.length,
					lines: lines.map((text) => ({ type: "add" as const, text })),
				},
			],
		};
	}

	const raw = await runGit(cwd, ["diff", "--", path]);
	return { path, hunks: parseUnifiedDiff(raw) };
}

function isTracked(cwd: string, path: string): Promise<boolean> {
	return runGit(cwd, ["ls-files", "--error-unmatch", "--", path])
		.then(() => true)
		.catch(() => false);
}

/** The status letter the client shows: worktree change wins over staged. */
function statusLetter(xy: string): GitChangeDto["status"] {
	if (xy === "??") return "U";
	const code = xy[0] !== " " ? xy[0] : xy[1];
	switch (code) {
		case "A":
			return "A";
		case "D":
			return "D";
		case "R":
			return "R";
		case "C":
			return "C";
		case "T":
			return "T";
		default:
			return "M";
	}
}

function countLines(file: string): number {
	try {
		const text = readFileSync(file, "utf8");
		return text.length === 0 ? 0 : text.split("\n").length;
	} catch {
		return 0;
	}
}

function validatePath(cwd: string, path: string): string {
	const resolved = resolve(cwd, path);
	const base = resolve(cwd);
	if (resolved !== base && !resolved.startsWith(base + sep)) {
		throw new HttpError(400, "file must stay inside the repo", "bad_file");
	}
	return resolved;
}

const HUNK_HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;

function parseUnifiedDiff(raw: string): GitHunkDto[] {
	const hunks: GitHunkDto[] = [];
	let current: GitHunkDto | undefined;
	for (const line of raw.split("\n")) {
		const header = HUNK_HEADER.exec(line);
		if (header) {
			current = {
				oldStart: Number(header[1]),
				oldCount: Number(header[2] ?? 1),
				newStart: Number(header[3]),
				newCount: Number(header[4] ?? 1),
				lines: [],
			};
			hunks.push(current);
			continue;
		}
		if (!current) continue;
		if (line.startsWith("\\")) continue; // "\ No newline at end of file"
		if (line.startsWith("+")) current.lines.push({ type: "add", text: line.slice(1) });
		else if (line.startsWith("-")) current.lines.push({ type: "remove", text: line.slice(1) });
		else current.lines.push({ type: "context", text: line.startsWith(" ") ? line.slice(1) : line });
	}
	return hunks;
}
