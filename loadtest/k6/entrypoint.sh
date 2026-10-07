#!/bin/sh
set -eu
# ECS secrets injection; tokens live only in the task filesystem, never in the image.
if [ -n "${MANYAK_AUTH_JWT_SECRET:-}" ]; then
  python3 /scripts/gen-tokens.py --count "${USER_COUNT:-500}" --ttl "${TOKEN_TTL:-14400}" --issuer "${JWT_ISSUER:-manyak}" --out /tmp/tokens.json
  export TOKENS_FILE=/tmp/tokens.json
  unset MANYAK_AUTH_JWT_SECRET
fi
exec k6 run "$@"
