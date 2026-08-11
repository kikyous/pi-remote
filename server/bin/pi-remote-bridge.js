#!/usr/bin/env node
// CLI entry for pi-remote-bridge. The server source is TypeScript (with .ts
// extension imports), so this launcher registers the tsx loader — a real
// dependency — and boots src/index.ts directly. No build step needed.
import { register } from "tsx/esm/api";

register();
await import("../src/index.ts");
