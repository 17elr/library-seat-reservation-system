"""Exercise additive migration and code rollback in a NEW disposable copy.

Requires --dump (old backup), --legacy-jar (built from legacy tag), mysql CLI,
JDK21+, and DB_USER/DB_PASSWORD. Never restores over a nonempty database.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request
from datetime import datetime, timedelta, timezone


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dump', required=True, type=Path)
    p.add_argument('--legacy-jar', required=True, type=Path)
    p.add_argument('--port', default='13318')
    p.add_argument('--app-port', default='0')
    a = p.parse_args()
    if a.app_port == '0':
        with socket.socket() as free:
            free.bind(('127.0.0.1',0))
            a.app_port=str(free.getsockname()[1])
    name = 'library_seat_rehearsal_' + datetime.now().strftime('%Y%m%d%H%M%S')
    dest = Path('.local') / name
    dest.mkdir(parents=True)
    mysql = shutil.which('mysql') or str(Path(os.getenv('MYSQL_BIN', 'C:/Program Files/MySQL/MySQL Server 8.0/bin')) / 'mysql.exe')
    env = {**os.environ, 'MYSQL_PWD': os.getenv('DB_PASSWORD', '')}
    def sql(query, database=None):
        cmd = [mysql, '--no-defaults', '--host=127.0.0.1', '--port='+a.port,
               '--user='+os.getenv('DB_USER', 'library'), '--default-character-set=utf8mb4', '-N', '-B']
        if database:
            cmd.append(database)
        return subprocess.check_output(cmd, input=query.encode('utf-8'), env=env).decode('utf-8')
    sql('CREATE DATABASE `' + name + '` CHARACTER SET utf8mb4')
    def tool(action, file):
        subprocess.run(['python', 'scripts/db-tool.py', action, '--port', a.port,
                        '--database', name, '--file', str(file)], check=True)
    tool('restore-copy', a.dump)
    sql("""
    INSERT INTO system_rule(id,effective_at,advance_days,max_duration_minutes,checkin_early_minutes,checkin_grace_minutes,leave_minutes,leave_limit,violation_window_days,violation_limit,block_days)
      VALUES(1000000,'2026-01-01',2,240,10,10,30,2,7,3,3);
    INSERT INTO reservation(id,reservation_no,user_id,seat_id,start_time,end_time,status,rule_id,request_key,checkin_at,finished_at)
      SELECT 1000000,'FIXTURE-COMPLETED',u.id,s.id,'2026-10-01 10:30:00','2026-10-01 12:30:00','COMPLETED',q.id,'fixture-completed','2026-10-01 10:30:00','2026-10-01 12:30:00' FROM sys_user u,seat s,system_rule q WHERE u.student_no='20260001' AND s.id=(SELECT MIN(id) FROM seat) AND q.id=(SELECT MIN(id) FROM system_rule);
    INSERT INTO reservation(id,reservation_no,user_id,seat_id,start_time,end_time,status,rule_id,request_key,finished_at)
      SELECT 1000001,'FIXTURE-NOSHOW',u.id,s.id,'2026-10-02 10:30:00','2026-10-02 12:30:00','NO_SHOW',1000000,'fixture-noshow','2026-10-02 10:40:00' FROM sys_user u,seat s WHERE u.student_no='20260001' AND s.id=(SELECT MIN(id) FROM seat);
    INSERT INTO usage_session(reservation_id,start_time,end_time,end_reason) VALUES(1000000,'2026-10-01 10:30:00','2026-10-01 12:30:00','fixture');
    INSERT INTO reservation_event(reservation_id,from_status,to_status,event_type,occurred_at,reason) VALUES(1000000,NULL,'RESERVED','CREATE','2026-10-01 10:00:00','fixture'),(1000000,'CHECKED_IN','COMPLETED','COMPLETED','2026-10-01 12:30:00','fixture'),(1000001,'RESERVED','NO_SHOW','NO_SHOW','2026-10-02 10:40:00','fixture');
    INSERT INTO violation_record(reservation_id,user_id,type,occurred_at,reason) SELECT id,user_id,'NO_SHOW','2026-10-02 10:40:00','fixture' FROM reservation WHERE id=1000001;
    INSERT INTO audit_log(operator_id,operation,target_type,target_id,reason) SELECT id,'fixture','reservation',1000000,'fixture' FROM sys_user WHERE role='ADMIN' LIMIT 1;
    INSERT INTO room_opening(room_id,special_date,is_closed,open_time,close_time,reason) SELECT MIN(id),'2026-10-01',0,'08:00','22:00','fixture' FROM reading_room;
    """, name)
    tool('snapshot', dest / 'before.json')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    appenv = {**os.environ, 'DB_URL': f'jdbc:mysql://127.0.0.1:{a.port}/{name}?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
              'SERVER_PORT': a.app_port, 'SPRING_PROFILES_ACTIVE': 'prod'}
    process = None
    logfiles = []
    def start(jar, label):
        nonlocal process
        with socket.socket() as check:
            check.settimeout(1)
            if check.connect_ex(('127.0.0.1',int(a.app_port))) == 0:
                raise RuntimeError('Rehearsal app port is occupied')
        out = open(dest / (label+'.log'), 'wb');logfiles.append(out)
        process = subprocess.Popen([java, '-jar', str(jar.resolve()), '--library.seed-demo=false'],
                                   stdout=out, stderr=subprocess.STDOUT, env=appenv,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        for _ in range(45):
            if process.poll() is not None:
                raise RuntimeError('App failed: ' + label)
            try:
                urllib.request.urlopen('http://127.0.0.1:'+a.app_port+'/login', timeout=1)
                return
            except OSError:
                time.sleep(1)
        raise RuntimeError('App readiness timeout: ' + label)
    def stop():
        nonlocal process
        if process:
            process.terminate();process.wait(timeout=20);process=None
    try:
        start(Path('target/library-seat-0.1.0.jar'), 'new')
        tool('compare', dest / 'before.json')
        assert sql("SELECT COUNT(*) FROM reservation a JOIN system_rule q ON q.id=a.rule_id WHERE a.checkin_deadline<>DATE_ADD(a.start_time,INTERVAL q.checkin_grace_minutes MINUTE)", name).strip() == '0'
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        def api(path, body=None, token=None):
            headers = {'Content-Type': 'application/json'}
            if token:
                headers['X-CSRF-TOKEN'] = token
            req = urllib.request.Request('http://127.0.0.1:'+a.app_port+'/api'+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
            raw = opener.open(req, timeout=10).read()
            return json.loads(raw) if raw else None
        token = api('/auth/csrf')['token']
        token = api('/auth/login', {'account':'20260002','password':'Student123!'}, token)['token']
        day = (datetime.now(timezone(timedelta(hours=8)))+timedelta(days=1)).strftime('%Y-%m-%d')
        seat = int(sql('SELECT MIN(id) FROM seat', name))
        booking = api('/reservations', {'seatId':seat,'startTime':day+'T10:30:00','endTime':day+'T12:30:00','requestKey':'rehearsal-after-upgrade'}, token)
        api('/reservations/'+str(booking['id'])+'/cancel', {}, token)
        tool('backup', dest / 'pre-rollback.sql')
        stop();start(a.legacy_jar, 'legacy')
        tool('compare', dest / 'pre-rollback.json')
        stop();start(Path('target/library-seat-0.1.0.jar'), 'new-again')
        tool('compare', dest / 'pre-rollback.json')
        print('PASS: populated legacy data, old rule deadlines, new booking/events and code rollback: ' + name)
    finally:
        stop()
        for f in logfiles:
            f.close()


if __name__ == '__main__':
    main()
