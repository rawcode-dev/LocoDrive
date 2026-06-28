#!/bin/bash
set -e

BINARY=$(find target/gluonfx -type f -name "locodrive" | head -n 1)

if [ -z "$BINARY" ]; then
    echo "❌ Error: Native binary not found!"
    exit 1
fi

echo "Creating mock configuration..."
mkdir -p ~/.locodrive
cat << 'EOF' > ~/.locodrive/config.json
{
  "bindAddress": "127.0.0.1",
  "port": 8080,
  "guestEnabled": true,
  "users": [],
  "sharedFolders": [
    {
      "alias": "TestFolder",
      "path": "/",
      "guestAccessible": true,
      "readOnly": true
    }
  ]
}
EOF

echo "Starting LocoDrive native binary: $BINARY"
if command -v xvfb-run &> /dev/null; then
    xvfb-run --auto-servernum "$BINARY" &
else
    "$BINARY" &
fi
PID=$!

echo "Waiting 5 seconds for server to initialize..."
sleep 5

echo "Testing HTTP endpoint..."
if curl -s -f http://127.0.0.1:8080 > /dev/null; then
    echo "✅ Validation passed! Server responded to HTTP request."
    kill -9 $PID
    exit 0
else
    echo "❌ Validation failed! Server did not respond."
    kill -9 $PID
    exit 1
fi
