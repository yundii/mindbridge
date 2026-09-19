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
