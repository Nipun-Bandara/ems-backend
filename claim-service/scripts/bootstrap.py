#!/usr/bin/env python3
"""Export claim review scope through owning service APIs; apply a reviewed snapshot.

Pause source employee/profile, assignment and role writes during export.
The SYSTEM_ADMIN bearer token is read from CLAIM_BOOTSTRAP_TOKEN, never printed.
No database connection is made. See docs/claims.md for ordering and revocation.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default='http://localhost:8080')
    parser.add_argument('--input', type=Path, help='Use an exported snapshot and its original asOf')
    parser.add_argument('--apply', action='store_true', help='Import through administrator-only claim APIs')
    args = parser.parse_args()

    def call(path, method='GET', payload=None, optional=False):
        request = Request(args.gateway.rstrip('/') + '/api/' + path,
                          data=json.dumps(payload).encode() if payload is not None else None,
                          headers={'Authorization': 'Bearer ' + os.environ['CLAIM_BOOTSTRAP_TOKEN'],
                                   'Content-Type': 'application/json'}, method=method)
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as failure:
            if optional and failure.code == 404:
                return None
            raise SystemExit(f'{method} /api/{path.split("?")[0]} failed (HTTP {failure.code}); stopped. Reapply the same snapshot to resume.') from None

    if args.input:
        snapshot = json.loads(args.input.read_text())
    else:
        as_of = datetime.now(timezone.utc).isoformat()
        users = {}
        for assigned in ['true', 'false']:
            page = 0
            while True:
                result = call(f'users?assigned={assigned}&page={page}&limit=100')
                users.update((user['userId'], user) for user in result['users'])
                if not result['hasNext']:
                    break
                page += 1
        employees, reviewers = [], []
        for user_id, user in sorted(users.items()):
            profile = call(f'employees/{user_id}', optional=True)
            employees.append({'userId': user_id, 'active': bool(profile and profile['status'] == 'ACTIVE' and not user['isBanned']),
                              'departmentId': user['departmentId'],
                              'reportingManagerUserId': profile['reportingManagerUserId'] if profile else None})
            reviewers.append({'userId': user_id, 'role': 'HR_MANAGER' if 'HR_MANAGER' in user['roles'] else None})
        snapshot = {'asOf': as_of, 'employees': employees, 'reviewers': reviewers}
    if not args.apply:
        print(json.dumps(snapshot, indent=2))
        return
    for kind, route in [('employees', 'employees'), ('reviewers', 'reviewers')]:
        for row in snapshot[kind]:
            payload = {key: value for key, value in row.items() if key != 'userId'}
            payload['asOf'] = snapshot['asOf']
            call(f'claims/admin/{route}/{row["userId"]}', 'PUT', payload)
    print(f'Imported {len(snapshot["employees"])} employees and {len(snapshot["reviewers"])} reviewer records.')


if __name__ == '__main__':
    main()
