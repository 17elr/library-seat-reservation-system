CREATE TABLE IF NOT EXISTS write_guard (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO write_guard VALUES (1);

CREATE TABLE IF NOT EXISTS sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, student_no VARCHAR(32) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, name VARCHAR(50) NOT NULL, class_name VARCHAR(100) NOT NULL DEFAULT '',
 role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, blocked_until DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reading_room (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(60) NOT NULL,
 floor VARCHAR(40) NOT NULL, description VARCHAR(500) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE,
 open_time TIME NOT NULL, close_time TIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_opening (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, room_id BIGINT NOT NULL, special_date DATE NOT NULL,
 is_closed BOOLEAN NOT NULL, open_time TIME NOT NULL, close_time TIME NOT NULL,
 reason VARCHAR(200) NOT NULL, UNIQUE(room_id,special_date), FOREIGN KEY(room_id) REFERENCES reading_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seat (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, room_id BIGINT NOT NULL, seat_no VARCHAR(20) NOT NULL,
 row_no INT NOT NULL, col_no INT NOT NULL, has_socket BOOLEAN NOT NULL DEFAULT FALSE,
 near_window BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(room_id,seat_no), UNIQUE(room_id,row_no,col_no), FOREIGN KEY(room_id) REFERENCES reading_room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_rule (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, effective_at DATETIME NOT NULL,
 advance_days INT NOT NULL, max_duration_minutes INT NOT NULL,
 checkin_early_minutes INT NOT NULL, checkin_grace_minutes INT NOT NULL,
 leave_minutes INT NOT NULL, leave_limit INT NOT NULL,
 violation_window_days INT NOT NULL, violation_limit INT NOT NULL, block_days INT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, reservation_no VARCHAR(40) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, seat_id BIGINT NOT NULL, start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
 status VARCHAR(24) NOT NULL, checkin_at DATETIME NULL, leave_at DATETIME NULL, leave_deadline DATETIME NULL,
 leave_count INT NOT NULL DEFAULT 0, finished_at DATETIME NULL, finish_reason VARCHAR(200) NULL,
 rule_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,request_key), CHECK(start_time < end_time),
 INDEX idx_seat_time(seat_id,status,start_time,end_time), INDEX idx_user_status(user_id,status,start_time),
 INDEX idx_status_end(status,end_time),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(seat_id) REFERENCES seat(id),
 FOREIGN KEY(rule_id) REFERENCES system_rule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS usage_session (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, reservation_id BIGINT NOT NULL, start_time DATETIME NOT NULL,
 end_time DATETIME NULL, end_reason VARCHAR(100) NULL,
 INDEX idx_usage(reservation_id,start_time), FOREIGN KEY(reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, reservation_id BIGINT NOT NULL, from_status VARCHAR(24) NULL,
 to_status VARCHAR(24) NOT NULL, event_type VARCHAR(30) NOT NULL, occurred_at DATETIME NOT NULL,
 actor_id BIGINT NULL, reason VARCHAR(200) NOT NULL,
 FOREIGN KEY(reservation_id) REFERENCES reservation(id), FOREIGN KEY(actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS violation_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, reservation_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 type VARCHAR(24) NOT NULL, occurred_at DATETIME NOT NULL, reason VARCHAR(200) NOT NULL,
 is_revoked BOOLEAN NOT NULL DEFAULT FALSE, revoked_by BIGINT NULL, revoked_at DATETIME NULL, revoke_reason VARCHAR(200) NULL,
 UNIQUE(reservation_id,type), INDEX idx_violation_user(user_id,is_revoked,occurred_at),
 FOREIGN KEY(reservation_id) REFERENCES reservation(id), FOREIGN KEY(user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS announcement (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(100) NOT NULL, content TEXT NOT NULL,
 published BOOLEAN NOT NULL DEFAULT TRUE, created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, operator_id BIGINT NOT NULL, operation VARCHAR(60) NOT NULL,
 target_type VARCHAR(30) NOT NULL, target_id BIGINT NOT NULL, reason VARCHAR(200) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(operator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
