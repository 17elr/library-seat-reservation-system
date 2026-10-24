"""Back up or verify an offline database; restore only into an empty copy.

Passwords come from DB_PASSWORD, never command arguments or output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime

TABLES = ['sys_user', 'reading_room', 'room_opening', 'seat', 'system_rule',
          'reservation', 'usage_session', 'reservation_event', 'violation_record',
          'announcement', 'audit_log', 'write_guard']
ADDED = {'auth_version', 'checkin_deadline'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['backup', 'snapshot', 'compare', 'restore-copy'])
    p.add_argument('--host', default=os.getenv('DB_HOST', '127.0.0.1'))
    p.add_argument('--port', default=os.getenv('DB_PORT', '13318'))
    p.add_argument('--user', default=os.getenv('DB_USER', 'library'))
    p.add_argument('--database', default='library_seat')
    p.add_argument('--file', type=Path)
    a = p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_]+', a.database):
        p.error('Database name must be a simple identifier')
    bindir = Path(os.getenv('MYSQL_BIN', 'C:/Program Files/MySQL/MySQL Server 8.0/bin'))
    def binary(name):
        found = shutil.which(name)
        return found or str(bindir / (name + '.exe'))
    common = ['--no-defaults', '--host=' + a.host, '--port=' + a.port,
              '--user=' + a.user, '--default-character-set=utf8mb4']
    env = {**os.environ, 'MYSQL_PWD': os.getenv('DB_PASSWORD', '')}
    def query(sql):
        result = subprocess.run([binary('mysql'), *common, '--batch', '--raw',
                                 '--skip-column-names', a.database], input=sql.encode(),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
        return result.stdout
    def snapshot():
        data = {}
        for table in TABLES:
            cols = query(f"SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' ORDER BY ordinal_position").decode().splitlines()
            cols = [c for c in cols if c not in ADDED]
            if not cols:
                raise RuntimeError('Missing table: ' + table)
            rows = query('SELECT JSON_ARRAY(' + ','.join('`' + c + '`' for c in cols) + f') FROM `{table}` ORDER BY id')
            data[table] = {'columns': cols, 'rows': len(rows.splitlines()), 'sha256': hashlib.sha256(rows).hexdigest()}
        return data
    if a.action == 'backup':
        dest = a.file or Path('.local/backups') / (a.database + '-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.sql')
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            raise RuntimeError('Refusing to overwrite a backup')
        subprocess.run([binary('mysqldump'), *common, '--single-transaction', '--routines',
                        '--triggers', '--hex-blob', '--set-gtid-purged=OFF', '--no-tablespaces',
                        '--result-file=' + str(dest.resolve()), a.database], env=env, check=True)
        dest.with_suffix('.json').write_text(json.dumps(snapshot(), indent=2), encoding='utf-8')
        print(str(dest.resolve()))
    elif a.action == 'restore-copy':
        if not a.file or not a.file.is_file():
            p.error('--file is required')
        count = int(query('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'))
        if count:
            raise RuntimeError('Restore is allowed only into an empty database. Create a separate copy first.')
        query(a.file.read_text(encoding='utf-8'))
        print('Restored into empty copy: ' + a.database)
    else:
        if not a.file:
            p.error('--file is required')
        current = snapshot()
        if a.action == 'compare':
            expected = json.loads(a.file.read_text(encoding='utf-8'))
            changed = [t for t in TABLES if expected[t] != current[t]]
            if changed:
                raise RuntimeError('Changed tables: ' + ', '.join(changed))
            print('PASS: all 12 tables have identical original columns, counts and row digests')
        else:
            if a.file.exists():
                raise RuntimeError('Refusing to overwrite a snapshot')
            a.file.parent.mkdir(parents=True, exist_ok=True)
            a.file.write_text(json.dumps(current, indent=2), encoding='utf-8')
            print(json.dumps({k: v['rows'] for k, v in current.items()}))


if __name__ == '__main__':
    main()
