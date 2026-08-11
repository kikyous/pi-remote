# pi-remote-bridge

LAN bridge server for [pi-remote](https://github.com/kikyous/pi-remote) — exposes the
[pi](https://github.com/nicedoc/pi) coding agent over HTTP + WebSocket so the Pi Remote
Android app can browse sessions and chat with the agent from a phone on the same network.

## Usage

```bash
npm install -g pi-remote-bridge
pi-remote-bridge          # listens on 0.0.0.0:30150, prints a pairing QR code
pi-remote-bridge -p 8080  # custom port
```

Requires Node.js >= 22.19. The shared auth token lives at `~/.pi/remote/token`
(generated on first run). Only run this on a trusted network — anyone with the
token can drive an agent that runs arbitrary commands.

## API

See the [repository README](https://github.com/kikyous/pi-remote) for the full API
table (projects / sessions / entries / prompt / abort / git, plus a WebSocket
event stream).
