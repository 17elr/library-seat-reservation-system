ALTER TABLE sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reservation ADD COLUMN checkin_deadline DATETIME NULL;
UPDATE reservation a JOIN system_rule q ON q.id=a.rule_id
 SET a.checkin_deadline=DATE_ADD(a.start_time,INTERVAL q.checkin_grace_minutes MINUTE);
ALTER TABLE reservation MODIFY checkin_deadline DATETIME NOT NULL;
CREATE INDEX idx_expiry_checkin ON reservation(status,checkin_deadline,id);
CREATE INDEX idx_expiry_leave ON reservation(status,leave_deadline,id);
CREATE INDEX idx_expiry_end ON reservation(status,end_time,id);
