.PHONY: dev

# Start the bridge server in dev mode (tsx watch — auto-restarts on file changes)
dev:
	cd server && npm run dev
