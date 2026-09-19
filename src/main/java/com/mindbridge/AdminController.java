package com.mindbridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.Files;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    public record Card(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=12000) String content,
                       @NotBlank @Size(max=200) String source,@NotNull @Size(max=500) String tags) {}
    private final ConversationStore store;
    private final JdbcTemplate db;
    private final ToolOrchestration tools;
    private final ExcelLedger ledger;
    public AdminController(ConversationStore store,JdbcTemplate db,ToolOrchestration tools,ExcelLedger ledger) {
        this.store=store;this.db=db;this.tools=tools;this.ledger=ledger;
    }
    @GetMapping("/reports") public Object reports() { return store.allReports(); }
    @GetMapping("/reports/{id}") public Object report(@PathVariable String id) {
        var rows=db.queryForList("SELECT * FROM reports WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return Map.of("report",rows.get(0),"notifications",db.queryForList("SELECT * FROM notification_records WHERE report_id=?",id),"events",db.queryForList("SELECT stage,outcome,created_at FROM tool_events WHERE report_id=? ORDER BY created_at,id",id));
    }
    @PostMapping("/reports/{id}/retry") public Object retry(@PathVariable String id) { tools.retry(id);return Map.of("status","READY"); }
    @GetMapping("/excel") public ResponseEntity<byte[]> excel() throws Exception {
        if(!Files.exists(ledger.path())) throw new ResponseStatusException(NOT_FOUND,"No ledger yet");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=mindbridge-reports.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(Files.readAllBytes(ledger.path()));
    }
    @PostMapping("/knowledge") public Object add(@Valid @RequestBody Card card) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO knowledge_cards(id,title,content,source,tags) VALUES (?,?,?,?,?)",id,card.title(),card.content(),card.source(),card.tags());
        return Map.of("id",id);
    }
    @PutMapping("/knowledge/{id}") public Object edit(@PathVariable String id,@Valid @RequestBody Card card) {
        if(db.update("UPDATE knowledge_cards SET title=?,content=?,source=?,tags=? WHERE id=?",card.title(),card.content(),card.source(),card.tags(),id)==0) throw new ResponseStatusException(NOT_FOUND,"Editable card not found");
        return Map.of("id",id);
    }
    @DeleteMapping("/knowledge/{id}") public Object delete(@PathVariable String id) {
        if(db.update("DELETE FROM knowledge_cards WHERE id=?",id)==0) throw new ResponseStatusException(NOT_FOUND,"Editable card not found");
        return Map.of("deleted",true);
    }
}
