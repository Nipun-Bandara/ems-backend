#!/usr/bin/env python3
"""Disposable end-to-end SMTP outage/recovery probe. Build both service jars first.
Uses Docker CLI, Java 21 and Python standard library only. Never uses configured/live DBs.
"""
import base64
import json
import socket
import socketserver
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'leave-service/target/verification/item30/notification'
OUT.mkdir(parents=True, exist_ok=True)
containers, processes, logs = [], [], []


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def container(image, exposed, *env):
    name = 'ems-saga-test-' + uuid.uuid4().hex[:12]
    args = ['docker', 'run', '-d', '--name', name, '-P']
    for value in env:
        args += ['-e', value]
    run(*args, image)
    containers.append(name)
    ports = {p: int(run('docker', 'port', name, f'{p}/tcp').splitlines()[0].rsplit(':', 1)[1]) for p in exposed}
    return name, ports


def eventually(action, seconds=90):
    deadline = time.monotonic() + seconds
    failure = None
    while time.monotonic() < deadline:
        try:
            result = action()
            if result:
                return result
        except Exception as error:
            failure = error
        time.sleep(.5)
    raise AssertionError(f'Timed out: {failure}')


def http(base, path, method='GET', body=None, actor=1, roles='EMPLOYEE', auth=None):
    headers = {'Content-Type': 'application/json', 'X-User-Id': str(actor), 'X-User-Roles': roles}
    if auth:
        headers['Authorization'] = 'Basic ' + base64.b64encode(auth.encode()).decode()
    request = Request(base + path, method=method, headers=headers, data=None if body is None else json.dumps(body).encode())
    with urlopen(request, timeout=10) as response:
        data = response.read()
        return json.loads(data) if data else True


def start(service, pgport, amqp, webport, smtp):
    log = (OUT / f'{service}-{len(processes)}.log').open('w')
    logs.append(log)
    args = ['java', '-jar', str(ROOT / service / f'target/{service}-0.0.1-SNAPSHOT.jar'),
            '--spring.profiles.active=test', f'--server.port={webport}',
            f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:{pgport}/fixture',
            '--spring.datasource.username=fixture', '--spring.datasource.password=fixture-only',
            '--spring.rabbitmq.host=127.0.0.1', f'--spring.rabbitmq.port={amqp}',
            '--spring.rabbitmq.username=guest', '--spring.rabbitmq.password=guest',
            '--spring.mail.host=127.0.0.1', f'--spring.mail.port={smtp}',
            '--ems.leave-notification.poll-ms=500', '--management.health.mail.enabled=false']
    process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT)
    processes.append(process)
    eventually(lambda: http(f'http://127.0.0.1:{webport}', '/actuator/health')['status'] == 'UP')
    return process


class SMTP(socketserver.StreamRequestHandler):
    messages = []

    def handle(self):
        self.wfile.write(b'220 fixture SMTP\r\n')
        data, receiving = [], False
        while line := self.rfile.readline():
            if receiving:
                if line == b'.\r\n':
                    self.messages.append(b''.join(data).decode())
                    self.wfile.write(b'250 accepted\r\n')
                    receiving = False
                else:
                    data.append(line)
            elif line.upper().startswith(b'DATA'):
                receiving = True
                self.wfile.write(b'354 continue\r\n')
            elif line.upper().startswith(b'QUIT'):
                self.wfile.write(b'221 bye\r\n')
                break
            elif line.upper().startswith(b'EHLO'):
                self.wfile.write(b'250 fixture\r\n')
            else:
                self.wfile.write(b'250 OK\r\n')


smtp_server = None
try:
    leave_db, lp = container('postgres:17', [5432], 'POSTGRES_DB=fixture', 'POSTGRES_USER=fixture', 'POSTGRES_PASSWORD=fixture-only')
    notification_db, np = container('postgres:17', [5432], 'POSTGRES_DB=fixture', 'POSTGRES_USER=fixture', 'POSTGRES_PASSWORD=fixture-only')
    broker, bp = container('rabbitmq:4-management', [5672, 15672])
    for db in [leave_db, notification_db]:
        eventually(lambda: run('docker', 'exec', db, 'pg_isready', '-U', 'fixture'))
    management = f'http://127.0.0.1:{bp[15672]}'
    eventually(lambda: http(management, '/api/overview', auth='guest:guest'))
    smtp, leave_port, notification_port = port(), port(), port()
    notification_process = start('notification-service', np[5432], bp[5672], notification_port, smtp)
    leave_process = start('leave-service', lp[5432], bp[5672], leave_port, smtp)
    base = f'http://127.0.0.1:{leave_port}'
    at = datetime.now(timezone.utc).isoformat()
    for employee in [1, 2]:
        http(base, f'/api/leaves/admin/employees/{employee}', 'PUT', {'active': True, 'departmentId': 7, 'reportingManagerUserId': 2 if employee == 1 else None, 'asOf': at}, roles='SYSTEM_ADMIN')
    type_ = http(base, '/api/leaves/types', 'POST', {'code': 'SAGA_TEST', 'name': 'Saga fixture', 'active': True}, roles='HR_MANAGER')
    http(base, '/api/leaves/entitlements', 'POST', {'employeeId': 1, 'typeId': type_['id'], 'year': 2026, 'days': '10.00', 'reason': 'Disposable fixture'}, roles='HR_MANAGER')
    request = http(base, '/api/leaves/requests', 'POST', {'typeId': type_['id'], 'startDate': '2026-12-15', 'endDate': '2026-12-15', 'dayFraction': 'FULL', 'reason': 'SMTP recovery test', 'idempotencyKey': 'request'})
    workflow = http(base, f"/api/leaves/requests/{request['id']}/approve", 'POST', {'version': 0, 'idempotencyKey': 'approve', 'reason': 'Coverage confirmed'}, actor=2)
    eventually(lambda: http(base, f"/api/leaves/requests/{request['id']}")['status'] == 'APPROVED')
    assert len(http(base, '/api/leaves/calendar')['absences']) == 1
    assert http(base, '/api/leaves/balances/me?year=2026')[0]['taken'] == '1.00'
    envelope = {'eventId': str(uuid.uuid4()), 'type': 'user.verified', 'occurredAt': at, 'correlationId': 'smtp-fixture', 'payload': {'userId': 1, 'email': 'employee@example.test', 'username': 'Fixture', 'verifiedAt': at}}

    def publish(event):
        return http(management, '/api/exchanges/%2F/ems.events/publish', 'POST', {'properties': {'content_type': 'application/json', 'delivery_mode': 2}, 'routing_key': event['type'], 'payload': json.dumps(event), 'payload_encoding': 'string'}, auth='guest:guest')

    publish(envelope)

    def sql(query):
        return run('docker', 'exec', notification_db, 'psql', '-U', 'fixture', '-d', 'fixture', '-Atc', query)

    eventually(lambda: sql('SELECT count(*) FROM leave_notification_delivery WHERE attempts>0 AND sent_at IS NULL') == '1')
    assert http(base, f"/api/leaves/requests/{request['id']}")['status'] == 'APPROVED'
    # Restart notification-service with pending delivery before bringing SMTP back.
    notification_process.terminate()
    notification_process.wait(timeout=30)
    start('notification-service', np[5432], bp[5672], notification_port, smtp)
    print('Before SMTP recovery:', sql('SELECT workflow_id,attempts,last_error FROM leave_notification_delivery'), 'contacts:', sql('SELECT count(*) FROM leave_notification_contact'), flush=True)
    smtp_server = socketserver.ThreadingTCPServer(('127.0.0.1', smtp), SMTP)
    threading.Thread(target=smtp_server.serve_forever, daemon=True).start()
    def delivered():
        snapshot = sql('SELECT attempts,last_error,sent_at IS NOT NULL FROM leave_notification_delivery')
        print('Delivery recovery:', snapshot, flush=True)
        return sql('SELECT count(*) FROM leave_notification_delivery WHERE sent_at IS NOT NULL') == '1'
    eventually(delivered)
    assert len([m for m in SMTP.messages if "<leave-" in m]) == 1
    assert '<leave-' + workflow['id'] + '@ems.local>' in next(m for m in SMTP.messages if '<leave-' in m)
    approved = {'eventId': str(uuid.uuid4()), 'type': 'leave.approved', 'occurredAt': at, 'correlationId': 'smtp-fixture', 'payload': {'workflowId': workflow['id'], 'requestId': request['id'], 'employeeId': 1, 'startDate': request['startDate'], 'endDate': request['endDate']}}
    publish(approved)
    publish(approved)
    time.sleep(3)
    assert sql('SELECT count(*) FROM leave_notification_delivery') == '1'
    assert len([m for m in SMTP.messages if "<leave-" in m]) == 1
    current = http(base, f"/api/leaves/requests/{request['id']}")
    http(base, f"/api/leaves/requests/{request['id']}/cancel", 'POST', {'version': current['version'], 'idempotencyKey': 'cancel', 'reason': 'Plans changed'})
    eventually(lambda: http(base, f"/api/leaves/requests/{request['id']}")['status'] == 'CANCELLED')
    assert not http(base, '/api/leaves/calendar')['absences']
    assert http(base, '/api/leaves/balances/me?year=2026')[0]['available'] == '10.00'
    print('PASS: real approval/debit/calendar; SMTP outage preserves APPROVED; persisted pending delivery survives notification restart; restored SMTP sends one logical email; duplicate event/workflow replay sends no extra email; approved cancellation restores 10.00 and clears calendar.', flush=True)
finally:
    if smtp_server:
        smtp_server.shutdown()
        smtp_server.server_close()
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    for log in logs:
        log.close()
    for name in reversed(containers):
        subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, check=False)
