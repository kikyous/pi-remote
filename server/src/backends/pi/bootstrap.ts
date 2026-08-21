import { createAgentSessionServices, type ModelRuntime } from "@earendil-works/pi-coding-agent";

/**
 * Shared `ModelRuntime` for the pi backend.
 *
 * A bare `ModelRuntime.create()` only knows the built-in providers. The models
 * added by pi *extensions* (e.g. `@router-for-me/pi-cliproxyapi-provider`, which
 * registers the `cliproxyapi` provider and its models from settings.json
 * `packages`) are only registered once the extension loader runs. That happens
 * inside `createAgentSessionServices()` → `DefaultResourceLoader` → registers
 * every extension's `pendingProviderRegistrations` into the returned runtime.
 *
 * Without it the App's model picker (which reads `GET /models` → `getAvailable()`)
 * would never see extension-provided models like CLIProxyAPI. So we build the
 * runtime through the same services path pi uses, once, and reuse it everywhere.
 */
let bootstrap: Promise<ModelRuntime> | undefined;

const globalDiagnostics: string[] = [];

export function getModelRuntime(): Promise<ModelRuntime> {
	bootstrap ??= (async () => {
		const services = await createAgentSessionServices({ cwd: process.cwd() });
		for (const d of services.diagnostics) {
			globalDiagnostics.push(`[pi-remote:${d.type}] ${d.message}`);
		}
		if (services.diagnostics.some((d) => d.type === "error")) {
			// The runtime is still usable (built-ins + whatever extensions loaded);
			// surface the errors but don't fail model listing.
			console.warn("pi extension diagnostics:\n" + globalDiagnostics.join("\n"));
		}
		return services.modelRuntime;
	})();
	return bootstrap;
}

/** Diagnostics collected while loading pi extensions, for logging/debugging. */
export function getBootstrapDiagnostics(): readonly string[] {
	return globalDiagnostics;
}
