#!/usr/bin/env bash
# Builds the debug APK and serves it over the local network so a phone on the
# same Wi-Fi can download and install it. Ctrl+C to stop.
#
# Usage: scripts/serve-apk.sh [--no-build] [--port N]
set -euo pipefail

cd "$(dirname "$0")/.."
PORT=8000
BUILD=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --port) PORT="$2"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

if [[ $BUILD == 1 ]]; then
  echo "Building…"
  ./gradlew assembleDebug -q --console=plain
fi

APK=app/build/outputs/apk/debug/app-debug.apk
[[ -f $APK ]] || { echo "No APK at $APK — run without --no-build." >&2; exit 1; }

IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
[[ -n $IP ]] || { echo "Couldn't find a Wi-Fi/Ethernet IP address." >&2; exit 1; }

# Serve only a temp dir holding the APK and a landing page, never the project itself.
SERVE_DIR=$(mktemp -d)
trap 'rm -rf "$SERVE_DIR"' EXIT
cp "$APK" "$SERVE_DIR/music-player.apk"
BUILT=$(date -r "$APK" "+%Y-%m-%d %H:%M")
cat > "$SERVE_DIR/index.html" <<EOF
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Music player</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:32px 16px;text-align:center;background:#f7f5ff;color:#1c1b1f}
a{display:inline-block;margin:24px 0;padding:16px 32px;border-radius:999px;background:#5a4ab0;color:#fff;
text-decoration:none;font-size:1.2em}p{color:#5f5c71}</style></head>
<body><h1>Music</h1><p>Debug build · $BUILT</p>
<a href="music-player.apk" download>Download &amp; install</a>
<p>Open the downloaded file to install. If Android blocks it, allow<br>
"Install unknown apps" for your browser when prompted.</p></body></html>
EOF

URL="http://$IP:$PORT/"
echo
if command -v qrencode >/dev/null; then qrencode -t ANSIUTF8 -m 2 "$URL"; fi
echo "Serving on $URL  (phone must be on the same Wi-Fi)"
echo "Ctrl+C to stop."
echo

# Small server that sends the right MIME type so Android treats the file as an installable APK.
python3 -I - "$SERVE_DIR" "$PORT" <<'PY'
import http.server, functools, sys
directory, port = sys.argv[1], int(sys.argv[2])
class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      ".apk": "application/vnd.android.package-archive"}
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()
    def log_message(self, fmt, *args):
        print(f"{self.client_address[0]}  {fmt % args}", flush=True)
http.server.ThreadingHTTPServer(("0.0.0.0", port), functools.partial(Handler, directory=directory)).serve_forever()
PY
