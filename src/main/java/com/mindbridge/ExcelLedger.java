package com.mindbridge;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExcelLedger {
    private final Path path;
    public ExcelLedger(@Value("${mindbridge.tools.excel-path}") String path) { this.path=Path.of(path).toAbsolutePath(); }
    public Path path() { return path; }
    /** Full deterministic snapshot, so crash/retry never appends a duplicate row. */
    public synchronized void write(List<Map<String,Object>> reports) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary=Files.createTempFile(path.getParent(),"mindbridge-",".xlsx");
        try {
            try(var workbook=new XSSFWorkbook()) {
                var sheet=workbook.createSheet("Safety reports");
                var header=sheet.createRow(0);
                String[] fields={"id","conversation_id","username","intent","risk","summary","reason","created_at","response_status"};
                var style=workbook.createCellStyle();var font=workbook.createFont();font.setBold(true);style.setFont(font);
                for(int c=0;c<fields.length;c++) { var cell=header.createCell(c);cell.setCellValue(fields[c]);cell.setCellStyle(style);sheet.setColumnWidth(c,Math.min(c==5?80:28,255)*256); }
                int rowNumber=1;
                for(var report:reports) {
                    var row=sheet.createRow(rowNumber++);
                    for(int c=0;c<fields.length;c++) {
                        // Explicit STRING cells: student text beginning '=' is never interpreted as a formula.
                        row.createCell(c).setCellValue(String.valueOf(report.get(fields[c])));
                    }
                }
                sheet.createFreezePane(0,1);
                try(var output=Files.newOutputStream(temporary)) { workbook.write(output); }
            }
            // Fail closed if the filesystem cannot atomically replace the ledger.
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
