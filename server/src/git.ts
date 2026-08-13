import { existsSync, readFileSync, statSync } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { delimiter, dirname, join, resolve, sep } from "node:path";

import { HttpError } from "./http.ts";
import type {
	GitChangeDto,
	GitCommitDiffDto,
	GitCommitDto,
	GitCommitsPageDto,
	GitDiffDto,
	GitFileDiffDto,
	GitHunkDto,
	GitStatusDto,
} from "./protocol.ts";

/**
 * Read-only git queries for the repository a session lives in.
 *
 * Only status and diff are exposed, both read-only, so the working tree is
 * never touched. Paths are still validated to stay inside the repo.
 */

/**
 * The git the shell would resolve, found once at startup.
 *
 * Spawning an absolute path skips the PATH search that execvp/posix_spawn
 * performs on every spawn: each candidate directory costs ~10ms+ on some
 * machines, and a long per-tool PATH (asdf, flutter, dotnet, …) added ~200ms
 * to every git spawn here. A single stat-based scan costs ~1ms and needs no
 * hardcoded install locations.
 */
function resolveGit(): string {
	const dirs = process.env.PATH?.split(delimiter) ?? [];
	for (const dir of dirs) {
		if (!dir) continue;
		for (const name of ["git", "git.exe"]) {
			const candidate = join(dir, name);
			if (existsSync(candidate)) return candidate;
		}
	}
	return "git"; // last resort: let spawn do the PATH search on every call
}

const GIT_BIN = resolveGit();

// With a resolved absolute binary the child does not need PATH for itself (git
// finds its helpers via exec-path, and the pager is disabled below); a minimal
// PATH avoids re-paying the search for anything git does spawn. The unresolved
// fallback keeps the inherited PATH so the OS can still find "git".
const GIT_PATH = GIT_BIN === "git" ? process.env.PATH : `${dirname(GIT_BIN)}:/usr/bin:/bin`;

function runGit(cwd: string, args: string[]): Promise<string> {
	return new Promise((resolve, reject) => {
		const child = spawn(GIT_BIN, args, {
			cwd,
			env: {
				...process.env,
				// git finds its own helpers via exec-path, not PATH; the pager is
				// disabled below. A minimal PATH avoids the slow search above.
				PATH: GIT_PATH,
				// status/diff refresh the index by default, which takes the index
				// lock — several read-only git processes on one repo then serialize
				// on it. These queries never need to write the index back.
				GIT_OPTIONAL_LOCKS: "0",
				GIT_PAGER: "cat",
				GIT_TERMINAL_PROMPT: "0",
			},
		});
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
	const [statusRaw, numstatRaw] = await Promise.all([
		// --branch folds the branch name into the first "## " record, so the
		// list and the top-bar title come from a single git status.
		// --untracked-files=all expands untracked directories to files, each
		// of which opens a real diff (a "dir/" row is not diffable).
		runGit(cwd, ["status", "--porcelain=v1", "-z", "--branch", "--untracked-files=all"]),
		// `git diff HEAD` covers staged + unstaged in one pass; the two-command
		// fallback serves repos that have no HEAD yet (fresh git init).
		runGit(cwd, ["diff", "HEAD", "--numstat"])
			.catch(() =>
				runGit(cwd, ["diff", "--numstat"])
					.then((a) => a + "\n" + runGit(cwd, ["diff", "--cached", "--numstat"]))
					.catch(() => ""),
			),
	]);

	const changes = new Map<string, GitChangeDto>();
	let branch = "";

	// -z records are NUL-separated "XY path"; with --branch the first record is
	// the "## branch" header. Renames/copies are two records: new path, then old.
	const records = statusRaw.split("\0");
	for (let i = 0; i < records.length; i++) {
		const record = records[i]!;
		if (record.startsWith("## ")) {
			if (branch === "") branch = parseBranchHeader(record);
			continue;
		}
		if (record.length < 4) continue; // "XY " + at least one path char
		const xy = record.slice(0, 2);
		let path = record.slice(3);
		if ((xy[0] === "R" || xy[0] === "C") && i + 1 < records.length) {
			// First record is the new path, which is what the list should show
			// (and what the diff screen can open); the old path follows and is
			// skipped so the numstat "old => new" rows match up by new path.
			i++;
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

	// Untracked files have no diff stat; count their lines directly. Read in
	// parallel and asynchronously so a big untracked tree cannot block the
	// event loop (readFileSync would stall every other request while it runs).
	await Promise.all(
		[...changes.values()].map(async (change) => {
			if (change.status !== "U") return;
			const file = resolve(cwd, change.path);
			const st = await stat(file).catch(() => null);
			if (st?.isFile()) {
				change.added = await countLines(file);
			}
		}),
	);

	return { branch, changes: [...changes.values()] };
}

/**
 * The first `git status --porcelain=v1 --branch` record, e.g.
 * "## main...origin/main [ahead 1]" → "main". Repos with no commits yet
 * ("## No commits yet on main") or a detached HEAD ("## HEAD (no branch)")
 * print placeholders where `git branch --show-current` answered with an empty
 * string — keep that behaviour so the title falls back to "Git" as before.
 */
function parseBranchHeader(record: string): string {
	const raw = record.slice(3);
	if (raw.startsWith("No commits yet") || raw.startsWith("HEAD (")) return "";
	const end = raw.indexOf("...");
	const head = (end === -1 ? raw : raw.slice(0, end)).trim();
	const space = head.indexOf(" ");
	return space === -1 ? head : head.slice(0, space);
}

export async function gitDiff(cwd: string, path: string): Promise<GitDiffDto> {
	const file = validatePath(cwd, path);

	// A directory has no diff; the list normally expands folders, but guard
	// anyway so a stray "dir/" row cannot blow up readFileSync below.
	if (existsSync(file) && statSync(file).isDirectory()) {
		return { path, hunks: [] };
	}

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

const LOG_FORMAT = "--format=%H%x00%h%x00%aN%x00%aI%x00%s%x00";

/**
 * A page of commit history, newest first.
 *
 * `git log --numstat` puts each commit's per-file line counts right after it,
 * which gives us the +x/-y summary in the same pass as the list.
 */
export async function gitCommits(
	cwd: string,
	limit: number,
	before: string | undefined,
): Promise<GitCommitsPageDto> {
	const args = ["log", "--numstat", LOG_FORMAT, "--max-count", String(limit)];
	if (before) args.push(before + "^");
	const raw = await runGit(cwd, args);

	const commits: GitCommitDto[] = [];
	let current: GitCommitDto | undefined;
	for (const line of raw.split("\n")) {
		if (line.includes("\0")) {
			// The format ends with %x00, so the split always yields a trailing
			// empty field — the five real ones are safe to default anyway.
			const [hash = "", shortHash = "", author = "", date = "", subject = ""] = line.split("\0");
			current = {
				hash,
				shortHash,
				author,
				date,
				subject: subject ?? "",
				added: 0,
				deleted: 0,
			};
			commits.push(current);
			continue;
		}
		// A numstat row: "adds\tdels\tpath" — merge commits have none.
		if (current && line.length > 0) {
			const [adds, dels] = line.split("\t");
			const added = Number(adds);
			const deleted = Number(dels);
			if (Number.isInteger(added) && Number.isInteger(deleted)) {
				current.added += added;
				current.deleted += deleted;
			}
		}
	}

	let nextCursor: string | null = null;
	const last = commits.at(-1);
	if (last) {
		const parent = await runGit(cwd, ["rev-parse", "--verify", "--quiet", last.hash + "^"]).catch(() => "");
		nextCursor = parent.trim() || null;
	}
	return { commits, nextCursor };
}

/** One commit's full diff, split per file. */
export async function gitCommitDiff(cwd: string, sha: string): Promise<GitCommitDiffDto> {
	const raw = await runGit(cwd, ["show", "--format=", sha]);
	const [infoRaw] = await Promise.all([
		runGit(cwd, ["show", "--no-patch", "--format=%H%x00%h%x00%aN%x00%aI%x00%s%x00", sha]),
	]);
	const [hash, shortHash, author, date, subject] = infoRaw.split("\0");
	return {
		sha: hash ?? sha,
		shortHash: shortHash ?? sha.slice(0, 7),
		subject: subject ?? "",
		author: author ?? "",
		date: date ?? "",
		files: parseMultiFileDiff(raw),
	};
}

function parseMultiFileDiff(raw: string): GitFileDiffDto[] {
	const files: GitFileDiffDto[] = [];
	// Each file's section starts with a "diff --git" line.
	const parts = raw.split(/^diff --git /m).slice(1);
	for (const part of parts) {
		const firstLine = part.split("\n", 1)[0] ?? "";
		// "a/old b/new" — take the new path, or the old for deletions.
		const newPath = firstLine.split(" b/").pop() ?? "";
		files.push({
			path: newPath.replace(/^"|"$/g, ""),
			status: fileStatus(part),
			hunks: parseUnifiedDiff("diff --git " + part),
		});
	}
	return files;
}

function fileStatus(block: string): GitFileDiffDto["status"] {
	if (block.includes("new file mode")) return "A";
	if (block.includes("deleted file mode")) return "D";
	if (block.includes("rename from")) return "R";
	if (block.includes("copy from")) return "C";
	if (block.includes("old mode") || block.includes("new mode")) return "T";
	return "M";
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

async function countLines(file: string): Promise<number> {
	try {
		const text = await readFile(file, "utf8");
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
