package com.mindbridge;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocalNotification {
    private final JdbcTemplate db;
    public LocalNotification(JdbcTemplate db) { this.db=db; }
    public void record(String reportId) {
        if(db.queryForObject("SELECT COUNT(*) FROM notification_records WHERE report_id=?",Integer.class,reportId)==0)
            db.update("INSERT INTO notification_records(report_id,status,created_at) VALUES (?,?,?)",reportId,"LOCAL_RECORDED",Instant.now().toString());
    }
}
