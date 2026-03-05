package com.rawan.deadlock.report;

import com.rawan.deadlock.model.SQLStatement;

import java.util.*;

/**
 * Generates suggested fixes for deadlocks based on detected patterns.
 * Analyzes the deadlock cycle and recommends appropriate mitigation strategies.
 */
public class SuggestedFixGenerator {
    
    /**
     * Generates a suggested fix for a deadlock cycle based on pattern detection.
     * 
     * Patterns detected:
     * 1. SELECT then INSERT on same table → Separate SELECT into different transaction
     * 2. Two concurrent UPDATEs on same table → Use application-level locks
     * 3. ORM write-behind reordering → Move forward ORM flush
     * 4. Range lock blocking INSERT → Use MySQL UPSERT
     * 5. Default → Reorder SQL statements
     * 
     * @param cycle List of SQL statements forming the deadlock cycle
     * @return Suggested fix as a string
     */
    public String generateSuggestedFix(List<SQLStatement> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return "No deadlock cycle detected";
        }
        
        // Pattern 4: Range lock blocking INSERT (check before SELECT-then-INSERT)
        if (hasRangeLockBlockingInsertPattern(cycle)) {
            return "Use MySQL UPSERT (INSERT ON DUPLICATE KEY UPDATE)";
        }
        
        // Pattern 1: SELECT then INSERT on same table
        if (hasSelectThenInsertPattern(cycle)) {
            return "Separate SELECT into a different transaction";
        }
        
        // Pattern 2: Two concurrent UPDATEs on same table
        if (hasConcurrentUpdatesPattern(cycle)) {
            return "Use application-level locks to enforce serial execution";
        }
        
        // Pattern 3: ORM write-behind reordering pattern
        if (hasOrmWriteBehindPattern(cycle)) {
            return "Move forward ORM flush before transaction boundary";
        }
        
        // Default: Reorder SQL statements
        return "Reorder SQL statements to avoid circular waits";
    }
    
    /**
     * Detects SELECT then INSERT pattern on the same table.
     * This pattern occurs when a transaction checks for existence (SELECT)
     * and then inserts if not found, creating gap lock conflicts.
     */
    private boolean hasSelectThenInsertPattern(List<SQLStatement> cycle) {
        // Group statements by table
        Map<String, List<SQLStatement>> tableStatements = groupByTable(cycle);
        
        for (Map.Entry<String, List<SQLStatement>> entry : tableStatements.entrySet()) {
            List<SQLStatement> stmts = entry.getValue();
            
            // Look for SELECT followed by INSERT on same table
            for (int i = 0; i < stmts.size() - 1; i++) {
                SQLStatement current = stmts.get(i);
                SQLStatement next = stmts.get(i + 1);
                
                if (current.getStatementType() == SQLStatement.StatementType.SELECT &&
                    next.getStatementType() == SQLStatement.StatementType.INSERT) {
                    return true;
                }
            }
            
            // Also check wrap-around (last to first)
            if (stmts.size() >= 2) {
                SQLStatement last = stmts.get(stmts.size() - 1);
                SQLStatement first = stmts.get(0);
                
                if (last.getStatementType() == SQLStatement.StatementType.SELECT &&
                    first.getStatementType() == SQLStatement.StatementType.INSERT) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Detects concurrent UPDATEs pattern.
     * This pattern occurs when multiple transactions update the same table
     * in different orders, creating a cycle.
     */
    private boolean hasConcurrentUpdatesPattern(List<SQLStatement> cycle) {
        int updateCount = 0;
        Set<String> updatedTables = new HashSet<>();
        
        for (SQLStatement stmt : cycle) {
            if (stmt.getStatementType() == SQLStatement.StatementType.UPDATE) {
                updateCount++;
                if (stmt.getTableNames() != null) {
                    updatedTables.addAll(stmt.getTableNames());
                }
            }
        }
        
        // Pattern: Multiple UPDATEs on same table(s)
        return updateCount >= 2 && updatedTables.size() <= 2;
    }
    
    /**
     * Detects ORM write-behind reordering pattern.
     * This pattern occurs when ORM frameworks delay writes and reorder them,
     * causing unexpected lock acquisition order.
     */
    private boolean hasOrmWriteBehindPattern(List<SQLStatement> cycle) {
        // Check for mixed INSERT/UPDATE/DELETE operations
        // which is typical of ORM write-behind behavior
        boolean hasInsert = false;
        boolean hasUpdate = false;
        boolean hasDelete = false;
        
        for (SQLStatement stmt : cycle) {
            switch (stmt.getStatementType()) {
                case INSERT:
                    hasInsert = true;
                    break;
                case UPDATE:
                    hasUpdate = true;
                    break;
                case DELETE:
                    hasDelete = true;
                    break;
                default:
                    break;
            }
        }
        
        // Pattern: Mix of INSERT, UPDATE, and/or DELETE (at least 2 different types)
        int writeTypeCount = (hasInsert ? 1 : 0) + (hasUpdate ? 1 : 0) + (hasDelete ? 1 : 0);
        return writeTypeCount >= 2;
    }
    
    /**
     * Detects range lock blocking INSERT pattern.
     * This pattern occurs when a SELECT with range conditions creates gap locks
     * that block concurrent INSERTs.
     */
    private boolean hasRangeLockBlockingInsertPattern(List<SQLStatement> cycle) {
        boolean hasSelectWithRangeCondition = false;
        boolean hasInsert = false;
        
        for (SQLStatement stmt : cycle) {
            if (stmt.getStatementType() == SQLStatement.StatementType.SELECT) {
                // Check if SELECT has range conditions
                String conditions = stmt.getQueryConditions();
                if (conditions != null && hasRangeCondition(conditions)) {
                    hasSelectWithRangeCondition = true;
                }
            } else if (stmt.getStatementType() == SQLStatement.StatementType.INSERT) {
                hasInsert = true;
            }
        }
        
        return hasSelectWithRangeCondition && hasInsert;
    }
    
    /**
     * Checks if query conditions contain range operators.
     */
    private boolean hasRangeCondition(String conditions) {
        if (conditions == null) {
            return false;
        }
        
        String upper = conditions.toUpperCase();
        return upper.contains(">") || 
               upper.contains("<") || 
               upper.matches(".*\\bBETWEEN\\b.*") ||
               upper.matches(".*\\bLIKE\\b.*") ||
               upper.matches(".*\\bIN\\b.*\\(.*\\).*");
    }
    
    /**
     * Groups SQL statements by table name.
     */
    private Map<String, List<SQLStatement>> groupByTable(List<SQLStatement> cycle) {
        Map<String, List<SQLStatement>> grouped = new HashMap<>();
        
        for (SQLStatement stmt : cycle) {
            if (stmt.getTableNames() != null) {
                for (String table : stmt.getTableNames()) {
                    grouped.computeIfAbsent(table, k -> new ArrayList<>()).add(stmt);
                }
            }
        }
        
        return grouped;
    }
    
    /**
     * Generates a detailed conflict description for the deadlock cycle.
     * 
     * @param cycle List of SQL statements forming the deadlock cycle
     * @return Conflict description as a string
     */
    public String generateConflictDescription(List<SQLStatement> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return "No conflict detected";
        }
        
        StringBuilder description = new StringBuilder();
        description.append("Deadlock cycle detected with ").append(cycle.size()).append(" statements: ");
        
        for (int i = 0; i < cycle.size(); i++) {
            SQLStatement stmt = cycle.get(i);
            description.append(stmt.getStatementType())
                      .append(" on table '")
                      .append(stmt.getTableNames() != null ? String.join(", ", stmt.getTableNames()) : "unknown")
                      .append("'");
            
            if (i < cycle.size() - 1) {
                description.append(" → ");
            }
        }
        
        // Add pattern information
        if (hasSelectThenInsertPattern(cycle)) {
            description.append(". Pattern: SELECT-then-INSERT causing gap lock conflict");
        } else if (hasConcurrentUpdatesPattern(cycle)) {
            description.append(". Pattern: Concurrent UPDATEs in different order");
        } else if (hasOrmWriteBehindPattern(cycle)) {
            description.append(". Pattern: ORM write-behind reordering");
        } else if (hasRangeLockBlockingInsertPattern(cycle)) {
            description.append(". Pattern: Range lock blocking INSERT");
        }
        
        return description.toString();
    }
}
