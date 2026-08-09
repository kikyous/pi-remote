import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { homedir } from "node:os";
import { join } from "node:path";

export interface ServerConfig {
	port: number;
	host: string;
	token: string;
}

const DEFAULT_PORT = 30150;
const DEFAULT_HOST = "0.0.0.0";

function remoteDir(): string {
	return join(homedir(), ".pi", "remote");
}

/**
 * Read the shared token, generating one on first run.
 *
 * The token is the only thing standing between the LAN and an agent that can
 * run arbitrary commands, so it is generated rather than defaulted, and the
 * file is written 0600.
 */
export function loadOrCreateToken(): string {
	const dir = remoteDir();
	const file = join(dir, "token");
	try {
		const existing = readFileSync(file, "utf8").trim();
		if (existing.length > 0) return existing;
	} catch {
		// falls through to generation
	}
	const token = randomBytes(24).toString("base64url");
	mkdirSync(dir, { recursive: true, mode: 0o700 });
	writeFileSync(file, `${token}\n`, { mode: 0o600 });
	return token;
}

export function parseArgs(argv: string[]): ServerConfig {
	let port = DEFAULT_PORT;
	let host = DEFAULT_HOST;

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		if (arg === "--port" || arg === "-p") {
			const value = Number(argv[++i]);
			if (!Number.isInteger(value) || value < 1 || value > 65535) {
				throw new Error(`Invalid --port: ${argv[i]}`);
			}
			port = value;
		} else if (arg === "--host" || arg === "-H") {
			const value = argv[++i];
			if (!value) throw new Error("--host requires a value");
			host = value;
		} else if (arg === "--help" || arg === "-h") {
			printUsage();
			process.exit(0);
		} else {
			throw new Error(`Unknown option: ${arg}`);
		}
	}

	return { port, host, token: loadOrCreateToken() };
}

function printUsage(): void {
	console.log(`pi-remote-bridge - LAN bridge for the pi coding agent

Usage:
  pi-remote-bridge [options]

Options:
  --port, -p <port>   Port to listen on (default: ${DEFAULT_PORT})
  --host, -H <host>   Address to bind (default: ${DEFAULT_HOST}, all interfaces)
  --help, -h          Show this help

The shared token is stored in ~/.pi/remote/token and printed at startup.`);
}

/** Best-guess LAN addresses to print at startup, so the phone can be pointed at one. */
export function lanAddresses(): string[] {
	const out: string[] = [];
	for (const addrs of Object.values(networkInterfaces())) {
		for (const addr of addrs ?? []) {
			if (addr.family === "IPv4" && !addr.internal) out.push(addr.address);
		}
	}
	return out;
}
