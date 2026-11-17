#!/usr/bin/env python3
"""Create one-time Argentas staging device credentials without printing secrets."""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import secrets
from pathlib import Path


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device-id", required=True, help="Stable device ID (8-100 safe characters)")
    parser.add_argument("--label", required=True, help="Human-readable device label")
    parser.add_argument("--out-dir", default="argentas-device-secrets", help="Private output directory")
    args = parser.parse_args()

    if not re.fullmatch(r"[A-Za-z0-9_-]{8,100}", args.device_id):
        parser.error("--device-id must be 8-100 letters, digits, underscores or hyphens")
    if not args.label.strip() or len(args.label) > 100:
        parser.error("--label must contain 1-100 characters")

    out = Path(args.out_dir)
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        os.chmod(out, 0o700)
    except OSError:
        pass

    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    token_path = out / f"{args.device_id}.token"
    sql_path = out / f"{args.device_id}.sql"
    if token_path.exists() or sql_path.exists():
        parser.error("output already exists; choose a new device ID or directory")

    token_path.write_text(token + "\n", encoding="utf-8")
    sql = (
        "insert into public.argentas_sync_devices (device_id, token_hash, label)\n"
        f"values ({sql_literal(args.device_id)}, {sql_literal(token_hash)}, {sql_literal(args.label.strip())});\n"
    )
    sql_path.write_text(sql, encoding="utf-8")
    for path in (token_path, sql_path):
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass

    # Keep the raw credential out of stdout, CI logs and shell history.
    print(f"Credential created: {token_path}")
    print(f"Hash-only SQL created: {sql_path}")
    print("Transfer the .token file directly to that device; execute the .sql file only in ARGENTAS-Staging.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
