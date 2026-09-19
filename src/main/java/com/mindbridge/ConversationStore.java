package com.mindbridge;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
public class ConversationStore {
    private final JdbcTemplate db;
    public ConversationStore(JdbcTemplate db) { this.db=db; }
    public String create() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO conversations(id,created_at) VALUES (?,?)", id, Instant.now().toString());
        return id;
    }
    public void require(String id) {
        if(db.queryForObject("SELECT COUNT(*) FROM conversations WHERE id=?", Integer.class,id)==0)
            throw new ResponseStatusException(NOT_FOUND,"Conversation not found");
    }
    public List<Map<String,Object>> messages(String id) {
        require(id);
        return db.queryForList("SELECT role,content,risk,created_at FROM messages WHERE conversation_id=? ORDER BY created_at,id",id);
    }
    public void message(String id,String role,String content,String risk) {
        db.update("INSERT INTO messages(id,conversation_id,role,content,risk,created_at) VALUES (?,?,?,?,?,?)", UUID.randomUUID().toString(),id,role,content,risk,Instant.now().toString());
    }
    @Transactional
    public String accept(String id,String text,RiskAgent.Assessment risk) {
        require(id); message(id,"user",text,risk.level().name());
        if(risk.level()!=RiskAgent.Level.HIGH_RISK) return "";
        String alertId=UUID.randomUUID().toString();
        db.update("INSERT INTO alerts(id,conversation_id,reason,status,created_at) VALUES (?,?,?,?,?)", alertId,id,risk.reason(),"OPEN",Instant.now().toString());
        return alertId;
    }
    public List<Map<String,Object>> alerts() { return db.queryForList("SELECT * FROM alerts ORDER BY created_at DESC"); }
    public void acknowledge(String id) {
        if(db.update("UPDATE alerts SET status='ACKNOWLEDGED' WHERE id=?",id)==0) throw new ResponseStatusException(NOT_FOUND,"Alert not found");
    }
    public Map<String,Object> overview() {
        return Map.of("conversations",db.queryForObject("SELECT COUNT(*) FROM conversations",Long.class),
            "messages",db.queryForObject("SELECT COUNT(*) FROM messages WHERE role='user'",Long.class),
            "openAlerts",db.queryForObject("SELECT COUNT(*) FROM alerts WHERE status='OPEN'",Long.class));
    }
    public Map<String,Object> report(String id) {
        var history=messages(id);
        var counts=new LinkedHashMap<String,Long>();
        for(var level:RiskAgent.Level.values()) counts.put(level.name(),history.stream().filter(m->"user".equals(m.get("role"))&&level.name().equals(m.get("risk"))).count());
        return Map.of("conversationId",id,"generatedAt",Instant.now().toString(),"screeningCounts",counts,"messages",history,"note","Demo rule-based screening, not a diagnosis. Local alerts do not notify real counselors.");
    }
}
