package com.mindbridge;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tools-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "mindbridge.provider=demo", "mindbridge.tools.enabled=false"})
class ToolOrchestrationTest {
    @Autowired ConversationStore store;
    @Autowired AgentRuntimeService runtime;
    @Autowired ToolOrchestration tools;
    @Autowired JdbcTemplate db;
    @MockitoBean ExcelLedger ledger;
    @MockitoBean LocalNotification notifications;
    @BeforeEach void clearJobs() { db.update("DELETE FROM notification_records");db.update("DELETE FROM tool_jobs"); }
    String riskReport(boolean ready) {
        String id=store.create("student");
        var accepted=store.accept(id,"student","Synthetic: I want to hurt myself",runtime.prepare(id,"I want to hurt myself"));
        if(ready) store.finishReport(accepted.reportId(),true);
        return accepted.reportId();
    }
    Map<String,Object> job(String id) { return db.queryForMap("SELECT * FROM tool_jobs WHERE report_id=?",id); }
    @Test void waitsForReplyThenWritesExcelBeforeNotificationAndNeverRepeatsCompleteJob() throws Exception {
        String id=riskReport(false);tools.tick();verifyNoInteractions(ledger,notifications);
        store.finishReport(id,true);tools.tick();
        var order=inOrder(ledger,notifications);order.verify(ledger).write(anyList());order.verify(notifications).record(id);
        assertThat(job(id).get("status")).isEqualTo("COMPLETE");
        tools.tick();verify(notifications,times(1)).record(id);
        assertThatThrownBy(()->tools.retry(id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void excelFailureBlocksNotificationAndRetrySucceeds() throws Exception {
        String id=riskReport(true);doThrow(new IOException("Synthetic file failure")).doNothing().when(ledger).write(anyList());
        tools.tick();verifyNoInteractions(notifications);assertThat(job(id).get("status")).isEqualTo("RETRY");
        assertThat(job(id).get("notification_status")).isEqualTo("PENDING");
        tools.retry(id);tools.tick();verify(notifications).record(id);assertThat(job(id).get("status")).isEqualTo("COMPLETE");
    }
    @Test void notificationFailureKeepsExcelCheckpointAndExhaustsRetries() throws Exception {
        String id=riskReport(true);doThrow(new IllegalStateException("Synthetic notification failure")).when(notifications).record(id);
        for(int i=0;i<3;i++){db.update("UPDATE tool_jobs SET next_attempt=0 WHERE report_id=?",id);tools.tick();}
        assertThat(job(id).get("status")).isEqualTo("FAILED");assertThat(job(id).get("excel_status")).isEqualTo("SUCCEEDED");
        assertThat(job(id).get("attempts")).isEqualTo(3);tools.tick();verify(notifications,times(3)).record(id);
    }
    @Test void restartRecoveryAndLocalNotificationAreIdempotent() throws Exception {
        String id=riskReport(false);tools.recover();assertThat(job(id).get("status")).isEqualTo("READY");
        db.update("UPDATE tool_jobs SET status='RUNNING' WHERE report_id=?",id);tools.recover();assertThat(job(id).get("status")).isEqualTo("RETRY");
        var local=new LocalNotification(db);local.record(id);local.record(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_records WHERE report_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void realExcelSnapshotContainsNoDuplicateRowsOrFormulaInjection(@TempDir Path directory) throws Exception {
        var actual=new ExcelLedger(directory.resolve("ledger.xlsx").toString());
        Map<String,Object> report=new HashMap<>();
        for(String key:List.of("id","conversation_id","username","intent","risk","summary","reason","created_at","response_status"))report.put(key,key);
        report.put("summary","=HYPERLINK(\"https://example.invalid\")");
        actual.write(List.of(report));actual.write(List.of(report));
        try(var input=Files.newInputStream(actual.path());var workbook=new XSSFWorkbook(input)) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getCellType()).isEqualTo(CellType.STRING);
        }
    }
}
