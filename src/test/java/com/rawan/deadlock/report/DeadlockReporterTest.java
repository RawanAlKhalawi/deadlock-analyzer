package com.rawan.deadlock.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rawan.deadlock.analyzer.DeadlockAnalyzer.DeadlockResult;
import com.rawan.deadlock.model.SQLStatement;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for DeadlockReporter functionality.
 */
public class DeadlockReporterTest {
    
    private DeadlockReporter reporter;
    private SuggestedFixGenerator fixGenerator;
    
    @Before
    public void setUp() {
        reporter = new DeadlockReporter();
        fixGenerator = new SuggestedFixGenerator();
    }
    
    // ========== Empty/Null Input Tests ==========
    
    @Test
    public void testGenerateReportsEmptyDeadlockResult() {
        DeadlockResult result = new DeadlockResult();
        List<DeadlockReport> reports = reporter.generateReports(result);
        
        assertNotNull("Reports list should not be null", reports);
        assertTrue("Reports list should be empty for no deadlocks", reports.isEmpty());
    }
    
    @Test
    public void testGenerateReportsNullDeadlockResult() {
        List<DeadlockReport> reports = reporter.generateReports(null);
        
        assertNotNull("Reports list should not be null", reports);
        assertTrue("Reports list should be empty for null input", reports.isEmpty());
    }
    
    @Test
    public void testToJsonEmptyReports() {
        List<DeadlockReport> reports = new ArrayList<>();
        String json = reporter.toJson(reports);
        
        assertNotNull("JSON output should not be null", json);
        assertTrue("JSON should contain timestamp", json.contains("timestamp"));
        assertTrue("JSON should contain totalDeadlocksFound", json.contains("totalDeadlocksFound"));
        assertTrue("JSON should indicate 0 deadlocks", json.contains("\"totalDeadlocksFound\" : 0"));
    }
    
    @Test
    public void testToHtmlEmptyReports() {
        List<DeadlockReport> reports = new ArrayList<>();
        String html = reporter.toHtml(reports);
        
        assertNotNull("HTML output should not be null", html);
        assertTrue("HTML should contain title", html.contains("Deadlock Analysis Report"));
        assertTrue("HTML should indicate no deadlocks", html.contains("No deadlocks detected"));
        assertTrue("HTML should contain total count", html.contains("Total Deadlocks Found"));
    }
    
    // ========== Single Deadlock Tests ==========
    
    @Test
    public void testGenerateReportsSingleDeadlock() {
        DeadlockResult result = createDeadlockResultWithSingleCycle();
        List<DeadlockReport> reports = reporter.generateReports(result);
        
        assertNotNull("Reports list should not be null", reports);
        assertEquals("Should have exactly 1 report", 1, reports.size());
        
        DeadlockReport report = reports.get(0);
        assertEquals("Report ID should be 1", 1, report.getId());
        assertNotNull("Involved transaction IDs should not be null", report.getInvolvedTransactionIds());
        assertFalse("Involved transaction IDs should not be empty", report.getInvolvedTransactionIds().isEmpty());
        assertNotNull("Cycle should not be null", report.getCycle());
        assertEquals("Cycle should have 2 statements", 2, report.getCycle().size());
        assertNotNull("Conflict description should not be null", report.getConflictDescription());
        assertNotNull("Suggested fix should not be null", report.getSuggestedFix());
    }
    
    @Test
    public void testToJsonSingleDeadlock() throws Exception {
        DeadlockResult result = createDeadlockResultWithSingleCycle();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String json = reporter.toJson(reports);
        
        assertNotNull("JSON output should not be null", json);
        assertTrue("JSON should contain timestamp", json.contains("timestamp"));
        assertTrue("JSON should contain totalDeadlocksFound", json.contains("totalDeadlocksFound"));
        assertTrue("JSON should indicate 1 deadlock", json.contains("\"totalDeadlocksFound\" : 1"));
        assertTrue("JSON should contain deadlocks array", json.contains("\"deadlocks\""));
        assertTrue("JSON should contain id field", json.contains("\"id\""));
        assertTrue("JSON should contain involvedTransactions", json.contains("\"involvedTransactions\""));
        assertTrue("JSON should contain cycle", json.contains("\"cycle\""));
        assertTrue("JSON should contain conflictDescription", json.contains("\"conflictDescription\""));
        assertTrue("JSON should contain suggestedFix", json.contains("\"suggestedFix\""));
        
        // Verify it's valid JSON by parsing
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        assertNotNull("Parsed JSON should not be null", parsed);
        assertEquals("Total deadlocks should be 1", 1, parsed.get("totalDeadlocksFound"));
    }
    
    @Test
    public void testToHtmlSingleDeadlock() {
        DeadlockResult result = createDeadlockResultWithSingleCycle();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String html = reporter.toHtml(reports);
        
        assertNotNull("HTML output should not be null", html);
        assertTrue("HTML should contain DOCTYPE", html.contains("<!DOCTYPE html>"));
        assertTrue("HTML should contain title", html.contains("Deadlock Analysis Report"));
        assertTrue("HTML should contain summary table", html.contains("<table>"));
        assertTrue("HTML should contain table headers", html.contains("<th>ID</th>"));
        assertTrue("HTML should contain total count", html.contains("Total Deadlocks Found"));
        assertTrue("HTML should indicate 1 deadlock", html.contains("Total Deadlocks Found:</strong> 1"));
        assertTrue("HTML should contain detailed analysis", html.contains("Detailed Analysis"));
        assertTrue("HTML should contain deadlock cycle styling", html.contains("deadlock-cycle"));
        assertTrue("HTML should contain suggested fix styling", html.contains("suggested-fix"));
        assertTrue("HTML should contain green color for fix", html.contains("#388e3c"));
        assertTrue("HTML should contain red color for cycle", html.contains("#d32f2f"));
    }
    
    // ========== Multiple Deadlocks Tests ==========
    
    @Test
    public void testGenerateReportsMultipleDeadlocks() {
        DeadlockResult result = createDeadlockResultWithMultipleCycles();
        List<DeadlockReport> reports = reporter.generateReports(result);
        
        assertNotNull("Reports list should not be null", reports);
        assertEquals("Should have exactly 2 reports", 2, reports.size());
        
        // Check first report
        DeadlockReport report1 = reports.get(0);
        assertEquals("First report ID should be 1", 1, report1.getId());
        
        // Check second report
        DeadlockReport report2 = reports.get(1);
        assertEquals("Second report ID should be 2", 2, report2.getId());
    }
    
    @Test
    public void testToJsonMultipleDeadlocks() throws Exception {
        DeadlockResult result = createDeadlockResultWithMultipleCycles();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String json = reporter.toJson(reports);
        
        assertNotNull("JSON output should not be null", json);
        assertTrue("JSON should indicate 2 deadlocks", json.contains("\"totalDeadlocksFound\" : 2"));
        
        // Verify it's valid JSON by parsing
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        List<Map<String, Object>> deadlocks = (List<Map<String, Object>>) parsed.get("deadlocks");
        assertEquals("Should have 2 deadlock entries", 2, deadlocks.size());
    }
    
    @Test
    public void testToHtmlMultipleDeadlocks() {
        DeadlockResult result = createDeadlockResultWithMultipleCycles();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String html = reporter.toHtml(reports);
        
        assertNotNull("HTML output should not be null", html);
        assertTrue("HTML should indicate 2 deadlocks", html.contains("Total Deadlocks Found:</strong> 2"));
        assertTrue("HTML should contain Deadlock #1", html.contains("Deadlock #1"));
        assertTrue("HTML should contain Deadlock #2", html.contains("Deadlock #2"));
    }
    
    // ========== Suggested Fix Pattern Tests ==========
    
    @Test
    public void testSuggestedFixForSelectThenInsert() {
        List<SQLStatement> cycle = createSelectThenInsertCycle();
        String fix = fixGenerator.generateSuggestedFix(cycle);
        
        assertEquals("Should recommend separating SELECT", 
                    "Separate SELECT into a different transaction", fix);
    }
    
    @Test
    public void testSuggestedFixForConcurrentUpdates() {
        List<SQLStatement> cycle = createConcurrentUpdatesCycle();
        String fix = fixGenerator.generateSuggestedFix(cycle);
        
        assertEquals("Should recommend application-level locks", 
                    "Use application-level locks to enforce serial execution", fix);
    }
    
    @Test
    public void testSuggestedFixForOrmWriteBehind() {
        List<SQLStatement> cycle = createOrmWriteBehindCycle();
        String fix = fixGenerator.generateSuggestedFix(cycle);
        
        assertEquals("Should recommend moving ORM flush", 
                    "Move forward ORM flush before transaction boundary", fix);
    }
    
    @Test
    public void testSuggestedFixForRangeLockBlockingInsert() {
        List<SQLStatement> cycle = createRangeLockBlockingInsertCycle();
        String fix = fixGenerator.generateSuggestedFix(cycle);
        
        assertEquals("Should recommend UPSERT", 
                    "Use MySQL UPSERT (INSERT ON DUPLICATE KEY UPDATE)", fix);
    }
    
    @Test
    public void testSuggestedFixDefault() {
        List<SQLStatement> cycle = createDefaultCycle();
        String fix = fixGenerator.generateSuggestedFix(cycle);
        
        assertEquals("Should recommend reordering statements", 
                    "Reorder SQL statements to avoid circular waits", fix);
    }
    
    // ========== Conflict Description Tests ==========
    
    @Test
    public void testConflictDescription() {
        List<SQLStatement> cycle = createSelectThenInsertCycle();
        String description = fixGenerator.generateConflictDescription(cycle);
        
        assertNotNull("Conflict description should not be null", description);
        assertTrue("Description should contain cycle info", description.contains("Deadlock cycle detected"));
        assertTrue("Description should contain statement count", description.contains("2 statements"));
        assertTrue("Description should contain SELECT", description.contains("SELECT"));
        assertTrue("Description should contain INSERT", description.contains("INSERT"));
        assertTrue("Description should contain pattern info", description.contains("Pattern:"));
    }
    
    // ========== JSON Parsing Tests ==========
    
    @Test
    public void testJsonIsValidAndParseable() throws Exception {
        DeadlockResult result = createDeadlockResultWithSingleCycle();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String json = reporter.toJson(reports);
        
        // Parse JSON to verify it's valid
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        
        // Verify structure
        assertTrue("Should have timestamp", parsed.containsKey("timestamp"));
        assertTrue("Should have totalDeadlocksFound", parsed.containsKey("totalDeadlocksFound"));
        assertTrue("Should have deadlocks array", parsed.containsKey("deadlocks"));
        
        List<Map<String, Object>> deadlocks = (List<Map<String, Object>>) parsed.get("deadlocks");
        assertEquals("Should have 1 deadlock", 1, deadlocks.size());
        
        Map<String, Object> deadlock = deadlocks.get(0);
        assertTrue("Deadlock should have id", deadlock.containsKey("id"));
        assertTrue("Deadlock should have involvedTransactions", deadlock.containsKey("involvedTransactions"));
        assertTrue("Deadlock should have cycle", deadlock.containsKey("cycle"));
        assertTrue("Deadlock should have conflictDescription", deadlock.containsKey("conflictDescription"));
        assertTrue("Deadlock should have suggestedFix", deadlock.containsKey("suggestedFix"));
    }
    
    // ========== HTML Structure Tests ==========
    
    @Test
    public void testHtmlContainsRequiredElements() {
        DeadlockResult result = createDeadlockResultWithSingleCycle();
        List<DeadlockReport> reports = reporter.generateReports(result);
        String html = reporter.toHtml(reports);
        
        // Check for required HTML elements
        assertTrue("Should have DOCTYPE", html.contains("<!DOCTYPE html>"));
        assertTrue("Should have html tag", html.contains("<html>"));
        assertTrue("Should have head tag", html.contains("<head>"));
        assertTrue("Should have body tag", html.contains("<body>"));
        assertTrue("Should have style tag", html.contains("<style>"));
        assertTrue("Should have h1 tag", html.contains("<h1>"));
        assertTrue("Should have h2 tag", html.contains("<h2>"));
        assertTrue("Should have table tag", html.contains("<table>"));
        assertTrue("Should have th tags", html.contains("<th>"));
        assertTrue("Should have td tags", html.contains("<td>"));
        assertTrue("Should have closing html tag", html.contains("</html>"));
        
        // Check for CSS classes
        assertTrue("Should have deadlock-cycle class", html.contains("deadlock-cycle"));
        assertTrue("Should have suggested-fix class", html.contains("suggested-fix"));
        assertTrue("Should have conflict-description class", html.contains("conflict-description"));
        assertTrue("Should have summary class", html.contains("summary"));
    }
    
    // ========== Edge Cases ==========
    
    @Test
    public void testNullCycleInFix() {
        String fix = fixGenerator.generateSuggestedFix(null);
        assertEquals("Should handle null cycle", "No deadlock cycle detected", fix);
    }
    
    @Test
    public void testEmptyCycleInFix() {
        String fix = fixGenerator.generateSuggestedFix(new ArrayList<>());
        assertEquals("Should handle empty cycle", "No deadlock cycle detected", fix);
    }
    
    @Test
    public void testNullCycleInDescription() {
        String description = fixGenerator.generateConflictDescription(null);
        assertEquals("Should handle null cycle", "No conflict detected", description);
    }
    
    @Test
    public void testHtmlEscaping() {
        DeadlockResult result = new DeadlockResult();
        
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "name = '<script>'", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "name = '\"test\"'", 2);
        
        List<SQLStatement> cycle = Arrays.asList(stmt1, stmt2);
        result.addConfirmedDeadlock(cycle);
        
        List<DeadlockReport> reports = reporter.generateReports(result);
        String html = reporter.toHtml(reports);
        
        // Verify HTML entities are escaped
        assertFalse("Should escape script tags", html.contains("<script>"));
        assertTrue("Should contain escaped script tag", html.contains("&lt;script&gt;"));
        assertTrue("Should escape quotes", html.contains("&quot;"));
    }
    
    // ========== Helper Methods ==========
    
    private DeadlockResult createDeadlockResultWithSingleCycle() {
        DeadlockResult result = new DeadlockResult();
        
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        stmt1.setTriggeringCodeLocation("UserService.java:42");
        
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        stmt2.setTriggeringCodeLocation("UserService.java:45");
        
        List<SQLStatement> cycle = Arrays.asList(stmt1, stmt2);
        result.addConfirmedDeadlock(cycle);
        
        return result;
    }
    
    private DeadlockResult createDeadlockResultWithMultipleCycles() {
        DeadlockResult result = new DeadlockResult();
        
        // First cycle
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        result.addConfirmedDeadlock(Arrays.asList(stmt1, stmt2));
        
        // Second cycle
        SQLStatement stmt3 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("orders"), "id = 1", 1);
        SQLStatement stmt4 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2);
        result.addConfirmedDeadlock(Arrays.asList(stmt3, stmt4));
        
        return result;
    }
    
    private List<SQLStatement> createSelectThenInsertCycle() {
        SQLStatement select = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "email = 'test@example.com'", 1);
        SQLStatement insert = new SQLStatement(SQLStatement.StatementType.INSERT, Arrays.asList("users"), "", 2);
        return Arrays.asList(select, insert);
    }
    
    private List<SQLStatement> createConcurrentUpdatesCycle() {
        SQLStatement update1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("products"), "id = 1", 1);
        SQLStatement update2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("products"), "id = 2", 2);
        return Arrays.asList(update1, update2);
    }
    
    private List<SQLStatement> createOrmWriteBehindCycle() {
        SQLStatement insert = new SQLStatement(SQLStatement.StatementType.INSERT, Arrays.asList("orders"), "", 1);
        SQLStatement update = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("products"), "id = 1", 2);
        SQLStatement delete = new SQLStatement(SQLStatement.StatementType.DELETE, Arrays.asList("cart_items"), "id = 1", 3);
        return Arrays.asList(insert, update, delete);
    }
    
    private List<SQLStatement> createRangeLockBlockingInsertCycle() {
        SQLStatement select = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id > 100 AND id < 200", 1);
        SQLStatement insert = new SQLStatement(SQLStatement.StatementType.INSERT, Arrays.asList("users"), "", 2);
        return Arrays.asList(select, insert);
    }
    
    private List<SQLStatement> createDefaultCycle() {
        SQLStatement select = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("accounts"), "id = 1", 1);
        SQLStatement select2 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("accounts"), "id = 2", 2);
        return Arrays.asList(select, select2);
    }
}
