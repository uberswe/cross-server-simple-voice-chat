#!/bin/sh
# Prepares the docker demo:
#  - copies the built addon jar into docker/mods/ (mounted into both servers)
#  - downloads the Simple Voice Chat Velocity plugin into docker/velocity/plugins/
set -eu

cd "$(dirname "$0")"

mkdir -p mods velocity/plugins

JAR=$(ls -t ../build/libs/cross-server-simple-voice-chat-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "ERROR: build the mod first: ./gradlew shadowJar" >&2
  exit 1
fi
cp "$JAR" mods/
echo "Copied $(basename "$JAR") -> docker/mods/"

if ! ls velocity/plugins/voicechat-velocity-*.jar >/dev/null 2>&1; then
  echo "Downloading Simple Voice Chat Velocity plugin from Modrinth..."
  URL=$(curl -fsSL -H "User-Agent: cross-server-simple-voice-chat-demo" \
    "https://api.modrinth.com/v2/project/simple-voice-chat/version?loaders=%5B%22velocity%22%5D" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['files'][0]['url'])")
  curl -fsSL -o "velocity/plugins/$(basename "$URL")" "$URL"
  echo "Downloaded $(basename "$URL") -> docker/velocity/plugins/"
else
  echo "Simple Voice Chat Velocity plugin already present."
fi

echo "Done. Start the demo with: docker compose up"
