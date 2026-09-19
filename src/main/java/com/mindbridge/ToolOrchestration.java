package com.mindbridge;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class ToolOrchestration {
    private final JdbcTemplate db;
    private final ExcelLedger ledger;
    private final LocalNotification notifications;
    private final boolean enabled;
    public ToolOrchestration(JdbcTemplate db,ExcelLedger ledger,LocalNotification notifications,
                             @Value("${mindbridge.tools.enabled:true}") boolean enabled) {
        this.db=db;this.ledger=ledger;this.notifications=notifications;this.enabled=enabled;
    }
    @PostConstruct public void recover() {
        // Single application instance only. A prior process may have stopped between a tool and its checkpoint.
        db.update("UPDATE tool_jobs SET status='RETRY',next_attempt=0 WHERE status='RUNNING'");
        db.update("UPDATE reports SET response_status='INCOMPLETE' WHERE response_status='PENDING'");
        db.update("UPDATE tool_jobs SET status='READY' WHERE status='WAITING_RESPONSE'");
    }
    @Scheduled(fixedDelayString="${mindbridge.tools.poll-ms:2000}") public void scheduled() { if(enabled) tick(); }
    public synchronized void tick() {
        // Recovery for disconnected responses whose completion callback could not persist.
        long cutoff=System.currentTimeMillis()-120000;
        db.update("UPDATE reports SET response_status='INCOMPLETE' WHERE response_status='PENDING' AND id IN (SELECT report_id FROM tool_jobs WHERE status='WAITING_RESPONSE' AND created_at<?)",cutoff);
        db.update("UPDATE tool_jobs SET status='READY' WHERE status='WAITING_RESPONSE' AND created_at<?",cutoff);
        var jobs=db.queryForList("SELECT * FROM tool_jobs WHERE status IN ('READY','RETRY') AND next_attempt<=? ORDER BY created_at LIMIT 10",System.currentTimeMillis());
        for(var job:jobs) run(String.valueOf(job.get("report_id")));
    }
    private void run(String id) {
        if(db.update("UPDATE tool_jobs SET status='RUNNING',attempts=attempts+1 WHERE report_id=? AND status IN ('READY','RETRY')",id)!=1) return;
        String stage="EXCEL";
        try {
            // Rebuild on every retry, even after a checkpoint, to ensure the ledger actually exists.
            ledger.write(db.queryForList("SELECT * FROM reports WHERE intent='RISK' ORDER BY created_at,id"));
            db.update("UPDATE tool_jobs SET excel_status='SUCCEEDED',last_error='' WHERE report_id=?",id);
            event(id,"EXCEL","SUCCEEDED");
            stage="NOTIFICATION";
            notifications.record(id);
            event(id,"NOTIFICATION","LOCAL_RECORDED");
            db.update("UPDATE tool_jobs SET status='COMPLETE',notification_status='LOCAL_RECORDED',last_error='' WHERE report_id=?",id);
        } catch(Exception error) {
            event(id,stage,"FAILED");
            int attempts=db.queryForObject("SELECT attempts FROM tool_jobs WHERE report_id=?",Integer.class,id);
            String safeError=stage+" failed ("+error.getClass().getSimpleName()+"). Check local configuration and retry.";
            db.update("UPDATE tool_jobs SET status=?,last_error=?,next_attempt=? WHERE report_id=?",
                attempts>=3?"FAILED":"RETRY",safeError,System.currentTimeMillis()+Math.min(60000,1000L*(1L<<Math.min(attempts,6))),id);
        }
    }
    private void event(String reportId,String stage,String outcome) {
        db.update("INSERT INTO tool_events(id,report_id,stage,outcome,created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(),reportId,stage,outcome,Instant.now().toString());
    }
    public synchronized void retry(String id) {
        var rows=db.queryForList("SELECT status FROM tool_jobs WHERE report_id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Job not found");
        String state=String.valueOf(rows.get(0).get("status"));
        if(!Set.of("FAILED","RETRY").contains(state)) throw new ResponseStatusException(CONFLICT,"Only failed or retrying jobs can be retried");
        db.update("UPDATE tool_jobs SET status='READY',attempts=0,next_attempt=0 WHERE report_id=?",id);
    }
    @Configuration @EnableScheduling static class Scheduling {}
}
