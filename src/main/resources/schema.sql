CREATE TABLE IF NOT EXISTS conversations (
 id VARCHAR(36) PRIMARY KEY,
 created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 role VARCHAR(16) NOT NULL,
 content TEXT NOT NULL,
 risk VARCHAR(20) NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
CREATE TABLE IF NOT EXISTS alerts (
 id VARCHAR(36) PRIMARY KEY,
 conversation_id VARCHAR(36) NOT NULL,
 reason VARCHAR(255) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);

-- Additive V2 schema: legacy anonymous conversations deliberately have no owner.
CREATE TABLE IF NOT EXISTS conversation_owners (
 conversation_id VARCHAR(36) PRIMARY KEY, username VARCHAR(100) NOT NULL,
 FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
CREATE TABLE IF NOT EXISTS reports (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL,
 username VARCHAR(100) NOT NULL, intent VARCHAR(20) NOT NULL, risk VARCHAR(20) NOT NULL,
 summary TEXT NOT NULL, reason VARCHAR(255) NOT NULL, created_at VARCHAR(40) NOT NULL,
 response_status VARCHAR(20) NOT NULL,
 FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
CREATE TABLE IF NOT EXISTS tool_jobs (
 report_id VARCHAR(36) PRIMARY KEY, status VARCHAR(24) NOT NULL, attempts INT NOT NULL,
 excel_status VARCHAR(24) NOT NULL, notification_status VARCHAR(24) NOT NULL,
 last_error VARCHAR(255) NOT NULL, next_attempt BIGINT NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY (report_id) REFERENCES reports(id)
);
CREATE TABLE IF NOT EXISTS notification_records (
 report_id VARCHAR(36) PRIMARY KEY, status VARCHAR(24) NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY (report_id) REFERENCES reports(id)
);
CREATE TABLE IF NOT EXISTS knowledge_cards (
 id VARCHAR(36) PRIMARY KEY, title VARCHAR(160) NOT NULL, content TEXT NOT NULL,
 source VARCHAR(200) NOT NULL, tags VARCHAR(500) NOT NULL
);
CREATE TABLE IF NOT EXISTS tool_events (
 id VARCHAR(36) PRIMARY KEY, report_id VARCHAR(36) NOT NULL,
 stage VARCHAR(24) NOT NULL, outcome VARCHAR(24) NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY (report_id) REFERENCES reports(id)
);
