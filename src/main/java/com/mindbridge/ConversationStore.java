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
    @Transactional public String create(String owner) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO conversations(id,created_at) VALUES (?,?)",id,Instant.now().toString());
        db.update("INSERT INTO conversation_owners(conversation_id,username) VALUES (?,?)",id,owner);
        return id;
    }
    public void requireOwner(String id,String owner) {
        if(db.queryForObject("SELECT COUNT(*) FROM conversation_owners WHERE conversation_id=? AND username=?",Integer.class,id,owner)==0)
            throw new ResponseStatusException(NOT_FOUND,"Conversation not found");
    }
    public List<Map<String,Object>> sessions(String owner) {
        return db.queryForList("SELECT c.id,c.created_at FROM conversations c JOIN conversation_owners o ON o.conversation_id=c.id WHERE o.username=? ORDER BY c.created_at DESC",owner);
    }
    public List<Map<String,Object>> messages(String id) {
        return db.queryForList("SELECT role,content,risk,created_at FROM messages WHERE conversation_id=? ORDER BY created_at,id",id);
    }
    public List<Map<String,Object>> recentMessages(String id) {
        var rows=new ArrayList<>(db.queryForList("SELECT role,content,risk,created_at FROM messages WHERE conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 12",id));
        Collections.reverse(rows);return rows;
    }
    public void message(String id,String role,String content,String risk) {
        db.update("INSERT INTO messages(id,conversation_id,role,content,risk,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,role,content,risk,Instant.now().toString());
    }
    public boolean hasOpenAlert(String id) { return db.queryForObject("SELECT COUNT(*) FROM alerts WHERE conversation_id=? AND status='OPEN'",Integer.class,id)>0; }
    public record Accepted(String reportId,String alertId) {}
    @Transactional public Accepted accept(String id,String owner,String text,AgentRuntimeService.Plan plan) {
        requireOwner(id,owner);
        message(id,"user",text,plan.risk().level().name());
        if(plan.intent()==AgentRuntimeService.Intent.CHAT) return new Accepted("","");
        String reportId=UUID.randomUUID().toString();String alertId="";
        boolean high=plan.intent()==AgentRuntimeService.Intent.RISK;
        String summary="Rule-based screening: "+plan.risk().level()+". Student statement: "+text;
        db.update("INSERT INTO reports(id,conversation_id,username,intent,risk,summary,reason,created_at,response_status) VALUES (?,?,?,?,?,?,?,?,?)",
            reportId,id,owner,plan.intent().name(),plan.risk().level().name(),summary,plan.risk().reason(),Instant.now().toString(),"PENDING");
        if(high) {
            alertId=UUID.randomUUID().toString();
            db.update("INSERT INTO alerts(id,conversation_id,reason,status,created_at) VALUES (?,?,?,?,?)",alertId,id,plan.risk().reason(),"OPEN",Instant.now().toString());
            db.update("INSERT INTO tool_jobs(report_id,status,attempts,excel_status,notification_status,last_error,next_attempt,created_at) VALUES (?,?,?,?,?,?,?,?)",
                reportId,"WAITING_RESPONSE",0,"PENDING","PENDING","",0,System.currentTimeMillis());
        }
        return new Accepted(reportId,alertId);
    }
    @Transactional public void finishReport(String reportId,boolean complete) {
        if(reportId.isEmpty()) return;
        db.update("UPDATE reports SET response_status=? WHERE id=? AND response_status='PENDING'",complete?"COMPLETE":"INCOMPLETE",reportId);
        db.update("UPDATE tool_jobs SET status='READY' WHERE report_id=? AND status='WAITING_RESPONSE'",reportId);
    }
    public List<Map<String,Object>> alerts() { return db.queryForList("SELECT * FROM alerts ORDER BY created_at DESC"); }
    public void acknowledge(String id) {
        if(db.update("UPDATE alerts SET status='ACKNOWLEDGED' WHERE id=?",id)==0) throw new ResponseStatusException(NOT_FOUND,"Alert not found");
    }
    public Map<String,Object> overview(String owner,boolean admin) {
        if(admin) return Map.of("conversations",db.queryForObject("SELECT COUNT(*) FROM conversations",Long.class),
            "messages",db.queryForObject("SELECT COUNT(*) FROM messages WHERE role='user'",Long.class),
            "openAlerts",db.queryForObject("SELECT COUNT(*) FROM alerts WHERE status='OPEN'",Long.class));
        return Map.of("conversations",sessions(owner).size(),"messages",db.queryForObject("SELECT COUNT(*) FROM messages m JOIN conversation_owners o ON o.conversation_id=m.conversation_id WHERE o.username=? AND m.role='user'",Long.class,owner));
    }
    public List<Map<String,Object>> reports(String id) { return db.queryForList("SELECT * FROM reports WHERE conversation_id=? ORDER BY created_at DESC",id); }
    public List<Map<String,Object>> allReports() { return db.queryForList("SELECT r.*,j.status AS tool_status,j.excel_status,j.notification_status,j.attempts,j.last_error FROM reports r LEFT JOIN tool_jobs j ON j.report_id=r.id ORDER BY r.created_at DESC"); }
    public Map<String,Object> report(String id) {
        return Map.of("conversationId",id,"generatedAt",Instant.now().toString(),"reports",reports(id),"messages",messages(id),"note","Rule-based screening, not a diagnosis. CHAT turns do not create screening reports.");
    }
}
