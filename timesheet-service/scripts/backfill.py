#!/usr/bin/env python3
"""Export an API-owned snapshot; optionally import it into timesheet-service.

Pause org membership/project writes during export. See docs/timesheets.md.
The bearer token is read from TIMESHEET_BACKFILL_TOKEN and never printed.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--timezone", default="Asia/Colombo")
    parser.add_argument("--apply", action="store_true", help="Import after exporting through the APIs")
    parser.add_argument("--input", type=Path, help="Import/review an existing snapshot with its original watermark")
    args = parser.parse_args()
    token = os.environ["TIMESHEET_BACKFILL_TOKEN"]
    as_of = datetime.now(timezone.utc)

    def call(path, method="GET", payload=None, allow_missing=False):
        request = Request(args.gateway.rstrip("/") + "/api/" + path,
                          data=json.dumps(payload).encode() if payload is not None else None,
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
                          method=method)
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            raise SystemExit(f"Source or import request failed: {method} /api/{path.split('?')[0]} (HTTP {error.code}). No further batches imported.") from None

    def finish(snapshot):
        if not args.apply:
            print(json.dumps(snapshot, indent=2))
            return
        for key in ["employees", "projects", "memberships"]:
            for offset in range(0, len(snapshot[key]), 1000):
                batch = {**snapshot, "employees": [], "projects": [], "memberships": []}
                batch[key] = snapshot[key][offset:offset + 1000]
                call("timesheets/admin/projections", "PUT", batch)
        print(f"Imported {len(snapshot['employees'])} employees, {len(snapshot['projects'])} projects and {len(snapshot['memberships'])} memberships. Snapshot: {snapshot['asOf']}")

    if args.input:
        finish(json.loads(args.input.read_text()))
        return

    def pages(path, key):
        page = 0
        while True:
            data = call(f"{path}{'&' if '?' in path else '?'}page={page}&limit=100")
            yield from data[key]
            if not data["hasNext"]:
                break
            page += 1

    users = {u["userId"]: u for assigned in ["true", "false"]
             for u in pages(f"users?assigned={assigned}", "users")}
    employees = []
    for user_id, user in users.items():
        # Unverified users legitimately have no employee shell. Never activate them.
        profile = call(f"employees/{user_id}", allow_missing=True)
        employees.append({"userId": user_id, "active": profile is not None and profile["status"] == "ACTIVE" and not user["isBanned"]})
    projects = [{"id": p["id"], "teamId": p["teamId"], "name": p["name"], "deleted": False,
                 "startDate": p["startDate"], "endDate": p["endDate"]}
                for p in pages("projects", "projects")]
    memberships = [{"teamId": t["id"], "userId": m["userId"], "member": True}
                   for t in call("teams") for m in t["members"]]
    snapshot = {"asOf": as_of.isoformat(), "effectiveDate": as_of.astimezone(ZoneInfo(args.timezone)).date().isoformat(),
                "employees": employees, "projects": projects, "memberships": memberships}
    finish(snapshot)


if __name__ == "__main__":
    main()
