package com.rawan.deadlock.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rawan.deadlock.analyzer.DeadlockAnalyzer.DeadlockResult;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Generates reports from DeadlockResult in various formats (JSON, HTML).
 * Provides comprehensive deadlock analysis reports with suggested fixes.
 */
public class DeadlockReporter {
    
    private final SuggestedFixGenerator fixGenerator;
    private final ObjectMapper objectMapper;
    
    public DeadlockReporter() {
        this.fixGenerator = new SuggestedFixGenerator();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    /**
     * Generates DeadlockReport objects from DeadlockResult.
     * 
     * @param result DeadlockResult from analyzer
     * @return List of DeadlockReport objects
     */
    public List<DeadlockReport> generateReports(DeadlockResult result) {
        List<DeadlockReport> reports = new ArrayList<>();
        
        if (result == null || result.getConfirmedDeadlocks() == null) {
            return reports;
        }
        
        List<List<SQLStatement>> confirmedDeadlocks = result.getConfirmedDeadlocks();
        
        for (int i = 0; i < confirmedDeadlocks.size(); i++) {
            List<SQLStatement> cycle = confirmedDeadlocks.get(i);
            
            // Extract involved transaction IDs
            List<String> involvedTxIds = extractTransactionIds(cycle);
            
            // Generate conflict description
            String conflictDescription = fixGenerator.generateConflictDescription(cycle);
            
            // Generate suggested fix
            String suggestedFix = fixGenerator.generateSuggestedFix(cycle);
            
            // Create satisfying assignment (placeholder - would come from Z3 solver)
            Map<String, Object> satisfyingAssignment = new HashMap<>();
            
            // Create report
            DeadlockReport report = new DeadlockReport(
                i + 1,
                involvedTxIds,
                cycle,
                conflictDescription,
                satisfyingAssignment,
                suggestedFix
            );
            
            reports.add(report);
        }
        
        return reports;
    }
    
    /**
     * Converts list of DeadlockReport objects to JSON string.
     * 
     * Output format:
     * {
     *   "timestamp": "2024-01-01T00:00:00",
     *   "totalDeadlocksFound": 2,
     *   "deadlocks": [...]
     * }
     * 
     * @param reports List of DeadlockReport objects
     * @return JSON string
     */
    public String toJson(List<DeadlockReport> reports) {
        try {
            Map<String, Object> output = new HashMap<>();
            
            // Add timestamp
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            output.put("timestamp", dateFormat.format(new Date()));
            
            // Add total count
            output.put("totalDeadlocksFound", reports != null ? reports.size() : 0);
            
            // Add deadlocks array
            List<Map<String, Object>> deadlocksList = new ArrayList<>();
            
            if (reports != null) {
                for (DeadlockReport report : reports) {
                    Map<String, Object> deadlockMap = new HashMap<>();
                    deadlockMap.put("id", report.getId());
                    deadlockMap.put("involvedTransactions", report.getInvolvedTransactionIds());
                    
                    // Convert cycle to simplified format
                    List<Map<String, Object>> cycleList = new ArrayList<>();
                    List<SQLStatement> cycle = report.getCycle();
                    for (int i = 0; i < cycle.size(); i++) {
                        SQLStatement stmt = cycle.get(i);
                        Map<String, Object> stmtMap = new HashMap<>();
                        stmtMap.put("type", stmt.getStatementType().toString());
                        
                        // Determine the actual conflict table for this statement
                        String conflictTable = determineConflictTable(stmt, cycle, i);
                        stmtMap.put("table", conflictTable);
                        
                        stmtMap.put("conditions", stmt.getQueryConditions());
                        
                        String triggeringCode = stmt.getTriggeringCodeLocation();
                        if (triggeringCode != null && !triggeringCode.isEmpty()) {
                            stmtMap.put("triggeringCode", triggeringCode);
                        }
                        
                        cycleList.add(stmtMap);
                    }
                    deadlockMap.put("cycle", cycleList);
                    
                    deadlockMap.put("conflictDescription", report.getConflictDescription());
                    deadlockMap.put("satisfyingAssignment", report.getSatisfyingAssignment());
                    deadlockMap.put("suggestedFix", report.getSuggestedFix());
                    
                    deadlocksList.add(deadlockMap);
                }
            }
            
            output.put("deadlocks", deadlocksList);
            
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            return "{\"error\": \"Failed to generate JSON: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Converts list of DeadlockReport objects to HTML string.
     * 
     * Includes:
     * - Summary table (id, tables, fix)
     * - Per-deadlock section with cycle details
     * - Color: red for deadlock cycle, green for suggested fix
     * - Basic CSS inline styling
     * 
     * @param reports List of DeadlockReport objects
     * @return HTML string
     */
    public String toHtml(List<DeadlockReport> reports) {
        StringBuilder html = new StringBuilder();
        
        // HTML header with inline CSS
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <title>Deadlock Analysis Report</title>\n");
        html.append("  <style>\n");
        html.append("    body { font-family: Arial, sans-serif; margin: 20px; background-color: #f5f5f5; }\n");
        html.append("    h1 { color: #333; }\n");
        html.append("    h2 { color: #555; margin-top: 30px; }\n");
        html.append("    table { border-collapse: collapse; width: 100%; margin: 20px 0; background-color: white; }\n");
        html.append("    th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }\n");
        html.append("    th { background-color: #4CAF50; color: white; }\n");
        html.append("    tr:nth-child(even) { background-color: #f2f2f2; }\n");
        html.append("    .deadlock-cycle { color: #d32f2f; font-weight: bold; }\n");
        html.append("    .suggested-fix { color: #388e3c; font-weight: bold; background-color: #e8f5e9; padding: 10px; border-radius: 5px; margin: 10px 0; }\n");
        html.append("    .conflict-description { background-color: #fff3e0; padding: 10px; border-left: 4px solid #ff9800; margin: 10px 0; }\n");
        html.append("    .cycle-details { background-color: #ffebee; padding: 15px; border-radius: 5px; margin: 10px 0; }\n");
        html.append("    .statement { margin: 5px 0; padding: 5px; background-color: white; border-left: 3px solid #d32f2f; }\n");
        html.append("    .summary { background-color: #e3f2fd; padding: 15px; border-radius: 5px; margin: 20px 0; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        
        // Title
        html.append("  <h1>Deadlock Analysis Report</h1>\n");
        
        // Summary
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        html.append("  <div class=\"summary\">\n");
        html.append("    <p><strong>Generated:</strong> ").append(dateFormat.format(new Date())).append("</p>\n");
        html.append("    <p><strong>Total Deadlocks Found:</strong> ").append(reports != null ? reports.size() : 0).append("</p>\n");
        html.append("  </div>\n");
        
        if (reports == null || reports.isEmpty()) {
            html.append("  <p>No deadlocks detected.</p>\n");
        } else {
            // Summary table
            html.append("  <h2>Summary</h2>\n");
            html.append("  <table>\n");
            html.append("    <tr>\n");
            html.append("      <th>ID</th>\n");
            html.append("      <th>Involved Transactions</th>\n");
            html.append("      <th>Tables</th>\n");
            html.append("      <th>Suggested Fix</th>\n");
            html.append("    </tr>\n");
            
            for (DeadlockReport report : reports) {
                html.append("    <tr>\n");
                html.append("      <td>").append(report.getId()).append("</td>\n");
                html.append("      <td>").append(String.join(", ", report.getInvolvedTransactionIds())).append("</td>\n");
                
                // Extract unique conflict tables
                Set<String> tables = new HashSet<>();
                List<SQLStatement> cycle = report.getCycle();
                for (int i = 0; i < cycle.size(); i++) {
                    String conflictTable = determineConflictTable(cycle.get(i), cycle, i);
                    if (conflictTable != null) {
                        tables.add(conflictTable);
                    }
                }
                html.append("      <td>").append(String.join(", ", tables)).append("</td>\n");
                html.append("      <td>").append(escapeHtml(report.getSuggestedFix())).append("</td>\n");
                html.append("    </tr>\n");
            }
            
            html.append("  </table>\n");
            
            // Detailed sections for each deadlock
            html.append("  <h2>Detailed Analysis</h2>\n");
            
            for (DeadlockReport report : reports) {
                html.append("  <div style=\"margin: 30px 0; padding: 20px; background-color: white; border-radius: 5px;\">\n");
                html.append("    <h3>Deadlock #").append(report.getId()).append("</h3>\n");
                
                // Conflict description
                html.append("    <div class=\"conflict-description\">\n");
                html.append("      <strong>Conflict:</strong> ").append(escapeHtml(report.getConflictDescription())).append("\n");
                html.append("    </div>\n");
                
                // Cycle details
                html.append("    <div class=\"cycle-details\">\n");
                html.append("      <p class=\"deadlock-cycle\">Deadlock Cycle:</p>\n");
                
                List<SQLStatement> cycle = report.getCycle();
                for (int i = 0; i < cycle.size(); i++) {
                    SQLStatement stmt = cycle.get(i);
                    html.append("      <div class=\"statement\">\n");
                    html.append("        <strong>").append(i + 1).append(".</strong> ");
                    html.append("<strong>").append(stmt.getStatementType()).append("</strong> ");
                    
                    // Determine the actual conflict table
                    String conflictTable = determineConflictTable(stmt, cycle, i);
                    html.append("on table <strong>").append(escapeHtml(conflictTable)).append("</strong>");
                    
                    if (stmt.getQueryConditions() != null && !stmt.getQueryConditions().isEmpty()) {
                        html.append("<br>&nbsp;&nbsp;&nbsp;&nbsp;WHERE ").append(escapeHtml(stmt.getQueryConditions()));
                    }
                    
                    if (stmt.getTriggeringCodeLocation() != null && !stmt.getTriggeringCodeLocation().isEmpty()) {
                        html.append("<br>&nbsp;&nbsp;&nbsp;&nbsp;<em>Code: ").append(escapeHtml(stmt.getTriggeringCodeLocation())).append("</em>");
                    }
                    
                    html.append("\n      </div>\n");
                    
                    if (i < report.getCycle().size() - 1) {
                        html.append("      <div style=\"text-align: center; color: #d32f2f;\">↓</div>\n");
                    }
                }
                
                html.append("    </div>\n");
                
                // Suggested fix
                html.append("    <div class=\"suggested-fix\">\n");
                html.append("      <strong>💡 Suggested Fix:</strong> ").append(escapeHtml(report.getSuggestedFix())).append("\n");
                html.append("    </div>\n");
                
                html.append("  </div>\n");
            }
        }
        
        // HTML footer
        html.append("</body>\n");
        html.append("</html>\n");
        
        return html.toString();
    }
    
    /**
     * Determines the actual conflict table for a statement in a cycle.
     * For statements with multiple tables (JOINs), finds the table that overlaps
     * with the next statement in the cycle (the actual conflict table).
     * 
     * @param stmt The statement to analyze
     * @param cycle The full cycle of statements
     * @param index The index of stmt in the cycle
     * @return The conflict table name
     */
    private String determineConflictTable(SQLStatement stmt, List<SQLStatement> cycle, int index) {
        // For UPDATE/INSERT/DELETE, the first (and usually only) table is the conflict table
        if (stmt.getStatementType() == SQLStatement.StatementType.UPDATE ||
            stmt.getStatementType() == SQLStatement.StatementType.INSERT ||
            stmt.getStatementType() == SQLStatement.StatementType.DELETE) {
            return stmt.getTableName(); // Returns first table from tableNames
        }
        
        // For SELECT with multiple tables (JOINs), find the overlapping table with next statement
        if (stmt.getTableNames() != null && stmt.getTableNames().size() > 1) {
            // Get the next statement in the cycle (wrap around)
            int nextIndex = (index + 1) % cycle.size();
            SQLStatement nextStmt = cycle.get(nextIndex);
            
            // Find overlapping table between current and next statement
            if (nextStmt.getTableNames() != null) {
                for (String table : stmt.getTableNames()) {
                    if (nextStmt.getTableNames().contains(table)) {
                        return table; // Return the first overlapping table
                    }
                }
            }
        }
        
        // Fallback: return first table
        return stmt.getTableName();
    }
    
    /**
     * Extracts unique transaction IDs from a cycle of SQL statements.
     */
    private List<String> extractTransactionIds(List<SQLStatement> cycle) {
        Set<String> txIds = new LinkedHashSet<>();
        for (SQLStatement stmt : cycle) {
            if (stmt.getTransactionId() != null) {
                String cleanId = stmt.getTransactionId().replaceAll("_instance2$", "");
                txIds.add(cleanId);
            }
        }
        // Fallback: generate transaction IDs based on statement order numbers
        if (txIds.isEmpty() && cycle != null) {
            Set<Integer> seenOrders = new LinkedHashSet<>();
            for (SQLStatement stmt : cycle) {
                seenOrders.add(stmt.getExecutionOrder());
            }
            for (Integer order : seenOrders) {
                txIds.add("TX" + order);
            }
        }
        return new ArrayList<>(txIds);
    }
    
    /**
     * Escapes HTML special characters.
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
    
    /**
     * Gets the SuggestedFixGenerator used by this reporter.
     */
    public SuggestedFixGenerator getFixGenerator() {
        return fixGenerator;
    }
}
