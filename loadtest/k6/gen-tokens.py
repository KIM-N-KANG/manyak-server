#!/usr/bin/env python3
"""Access JWT only; never create a refresh family or print credentials."""
import argparse
import base64
import hashlib
import hmac
import json
import os
import time
from pathlib import Path


def public_id(index):
    return f"15960000-0000-4000-8000-{index:012d}"


def issue(secret, subject, issuer, issued_at, ttl):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    body = enc({"alg": "HS256"}) + b"." + enc({"iss": issuer, "sub": subject, "iat": issued_at, "exp": issued_at + ttl})
    signature = base64.urlsafe_b64encode(hmac.new(secret, body, hashlib.sha256).digest()).rstrip(b"=")
    return (body + b"." + signature).decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=500)
    parser.add_argument("--ttl", type=int, default=14400)
    parser.add_argument("--issuer", default="manyak")
    parser.add_argument("--out", default="tokens.json")
    args = parser.parse_args()
    secret = os.environ.get("MANYAK_AUTH_JWT_SECRET", "").encode()
    if len(secret) < 32 or not 1 <= args.count <= 100000 or args.ttl <= 0:
        parser.error("Require JWT secret >=32 UTF-8 bytes, count 1..100000, positive TTL")
    now = int(time.time())
    rows = [{"publicId": public_id(i), "token": issue(secret, public_id(i), args.issuer, now, args.ttl)} for i in range(1, args.count + 1)]
    path = Path(args.out)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as file:
        json.dump(rows, file)
    print(f"Wrote {args.count} access tokens to {path} (mode 0600); expires in {args.ttl}s")


if __name__ == "__main__":
    main()
