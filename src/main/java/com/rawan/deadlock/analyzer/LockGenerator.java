package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.model.Lock;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.analyzer.IndexAnalyzer.Index;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LockGenerator generates database locks for SQL statements based on MySQL InnoDB locking behavior.
 * Implements lock generation logic following Algorithm 2 from the WeSEER paper.
 */
public class LockGenerator {
    
    private IndexAnalyzer indexAnalyzer;
    private SQLParser sqlParser;
    
    public LockGenerator(IndexAnalyzer indexAnalyzer) {
        this.indexAnalyzer = indexAnalyzer;
        this.sqlParser = new SQLParser();
    }
    
    public LockGenerator() {
        this.indexAnalyzer = new IndexAnalyzer();
        this.sqlParser = new SQLParser();
    }
    
    /**
     * Generates shared locks for a SELECT statement.
     * 
     * Algorithm 2 from WeSEER paper:
     * - If result set is empty (isEmpty = true): generates gap locks or next-key locks
     * - If result set is not empty: generates record locks and possibly gap locks
     * 
     * @param statement The SELECT statement
     * @param isEmpty Whether the query result set is empty
     * @return List of shared locks
     */
    public List<Lock> generateSharedLocks(SQLStatement statement, boolean isEmpty) {
        List<Lock> locks = new ArrayList<>();
        
        if (statement == null || statement.getStatementType() != SQLStatement.StatementType.SELECT) {
            return locks;
        }
        
        String tableName = statement.getTableNames() != null && !statement.getTableNames().isEmpty() 
            ? statement.getTableNames().get(0) 
            : null;
        String queryConditions = statement.getQueryConditions();
        
        // Infer which indexes will be used
        List<Index> indexes = indexAnalyzer.inferIndexes(statement);
        
        for (Index index : indexes) {
            if (isEmpty) {
                // Empty result set: generate gap lock or next-key lock
                Lock gapLock = generateGapLock(index, queryConditions, Lock.LockMode.SHARED);
                locks.add(gapLock);
            } else {
                // Non-empty result set: generate record locks
                if (hasExactMatch(queryConditions, index)) {
                    // Exact match on unique index: single row lock
                    Lock rowLock = new Lock(index.getIndexName(), Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
                    addPredicatesFromConditions(rowLock, queryConditions);
                    locks.add(rowLock);
                } else if (hasRangeCondition(queryConditions)) {
                    // Range condition: range lock with next-key locking
                    Lock rangeLock = new Lock(index.getIndexName(), Lock.LockGranularity.RANGE, Lock.LockMode.SHARED);
                    addPredicatesFromConditions(rangeLock, queryConditions);
                    locks.add(rangeLock);
                    
                    // Add gap lock for the range
                    Lock gapLock = generateGapLock(index, queryConditions, Lock.LockMode.SHARED);
                    locks.add(gapLock);
                } else {
                    // Non-unique index or no index: may lock multiple rows
                    Lock rangeLock = new Lock(index.getIndexName(), Lock.LockGranularity.RANGE, Lock.LockMode.SHARED);
                    addPredicatesFromConditions(rangeLock, queryConditions);
                    locks.add(rangeLock);
                }
            }
        }
        
        // If no specific locks generated, create a table-level lock
        if (locks.isEmpty()) {
            Lock tableLock = new Lock(tableName + "_table", Lock.LockGranularity.TABLE, Lock.LockMode.SHARED);
            locks.add(tableLock);
        }
        
        return locks;
    }
    
    /**
     * Generates exclusive locks for UPDATE, INSERT, or DELETE statements.
     * 
     * Algorithm 2 from WeSEER paper:
     * - UPDATE: locks affected rows and may create gap locks
     * - INSERT: creates insert intention locks and gap locks
     * - DELETE: locks affected rows and creates gap locks
     * 
     * @param statement The UPDATE/INSERT/DELETE statement
     * @return List of exclusive locks
     */
    public List<Lock> generateExclusiveLocks(SQLStatement statement) {
        List<Lock> locks = new ArrayList<>();
        
        if (statement == null) {
            return locks;
        }
        
        SQLStatement.StatementType type = statement.getStatementType();
        if (type != SQLStatement.StatementType.UPDATE && 
            type != SQLStatement.StatementType.INSERT && 
            type != SQLStatement.StatementType.DELETE) {
            return locks;
        }
        
        String tableName = statement.getTableNames() != null && !statement.getTableNames().isEmpty() 
            ? statement.getTableNames().get(0) 
            : null;
        String queryConditions = statement.getQueryConditions();
        
        // Infer which indexes will be used
        List<Index> indexes = indexAnalyzer.inferIndexes(statement);
        
        switch (type) {
            case INSERT:
                locks.addAll(generateInsertLocks(statement, indexes));
                break;
                
            case UPDATE:
                locks.addAll(generateUpdateLocks(statement, indexes, queryConditions));
                break;
                
            case DELETE:
                locks.addAll(generateDeleteLocks(statement, indexes, queryConditions));
                break;
        }
        
        // If no specific locks generated, create a table-level lock
        if (locks.isEmpty()) {
            Lock tableLock = new Lock(tableName + "_table", Lock.LockGranularity.TABLE, Lock.LockMode.EXCLUSIVE);
            locks.add(tableLock);
        }
        
        return locks;
    }
    
    /**
     * Generates locks for INSERT statements.
     * INSERT creates insert intention locks and may create gap locks.
     */
    private List<Lock> generateInsertLocks(SQLStatement statement, List<Index> indexes) {
        List<Lock> locks = new ArrayList<>();
        
        for (Index index : indexes) {
            // Insert intention lock (represented as a gap lock with INSERT predicate)
            Lock insertIntentionLock = new Lock(index.getIndexName(), 
                                               Lock.LockGranularity.RANGE, 
                                               Lock.LockMode.EXCLUSIVE);
            insertIntentionLock.addPredicate("INSERT_INTENTION");
            
            // Add predicates from SQL parameters (inserted values)
            Map<String, Object> params = statement.getSqlParameters();
            if (params != null && !params.isEmpty()) {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    insertIntentionLock.addPredicate(entry.getKey() + " = " + entry.getValue());
                }
            }
            
            locks.add(insertIntentionLock);
            
            // After successful insert, acquire exclusive lock on the new row
            Lock rowLock = new Lock(index.getIndexName(), 
                                   Lock.LockGranularity.ROW, 
                                   Lock.LockMode.EXCLUSIVE);
            if (params != null && !params.isEmpty()) {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    rowLock.addPredicate(entry.getKey() + " = " + entry.getValue());
                }
            }
            locks.add(rowLock);
        }
        
        return locks;
    }
    
    /**
     * Generates locks for UPDATE statements.
     * UPDATE locks affected rows and may create gap locks for range conditions.
     */
    private List<Lock> generateUpdateLocks(SQLStatement statement, List<Index> indexes, String queryConditions) {
        List<Lock> locks = new ArrayList<>();
        
        for (Index index : indexes) {
            if (hasExactMatch(queryConditions, index)) {
                // Exact match on unique index: single row lock
                Lock rowLock = new Lock(index.getIndexName(), 
                                       Lock.LockGranularity.ROW, 
                                       Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rowLock, queryConditions);
                locks.add(rowLock);
            } else if (hasRangeCondition(queryConditions)) {
                // Range condition: range lock with next-key locking
                Lock rangeLock = new Lock(index.getIndexName(), 
                                         Lock.LockGranularity.RANGE, 
                                         Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rangeLock, queryConditions);
                locks.add(rangeLock);
                
                // Add gap lock for the range
                Lock gapLock = generateGapLock(index, queryConditions, Lock.LockMode.EXCLUSIVE);
                locks.add(gapLock);
            } else {
                // Non-unique index: may lock multiple rows
                Lock rangeLock = new Lock(index.getIndexName(), 
                                         Lock.LockGranularity.RANGE, 
                                         Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rangeLock, queryConditions);
                locks.add(rangeLock);
            }
        }
        
        return locks;
    }
    
    /**
     * Generates locks for DELETE statements.
     * DELETE locks affected rows and creates gap locks.
     */
    private List<Lock> generateDeleteLocks(SQLStatement statement, List<Index> indexes, String queryConditions) {
        List<Lock> locks = new ArrayList<>();
        
        for (Index index : indexes) {
            if (hasExactMatch(queryConditions, index)) {
                // Exact match: row lock + gap lock
                Lock rowLock = new Lock(index.getIndexName(), 
                                       Lock.LockGranularity.ROW, 
                                       Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rowLock, queryConditions);
                locks.add(rowLock);
                
                // Gap lock to prevent phantom reads
                Lock gapLock = generateGapLock(index, queryConditions, Lock.LockMode.EXCLUSIVE);
                locks.add(gapLock);
            } else if (hasRangeCondition(queryConditions)) {
                // Range condition: range lock with gap locks
                Lock rangeLock = new Lock(index.getIndexName(), 
                                         Lock.LockGranularity.RANGE, 
                                         Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rangeLock, queryConditions);
                locks.add(rangeLock);
                
                Lock gapLock = generateGapLock(index, queryConditions, Lock.LockMode.EXCLUSIVE);
                locks.add(gapLock);
            } else {
                // Non-unique index: range lock
                Lock rangeLock = new Lock(index.getIndexName(), 
                                         Lock.LockGranularity.RANGE, 
                                         Lock.LockMode.EXCLUSIVE);
                addPredicatesFromConditions(rangeLock, queryConditions);
                locks.add(rangeLock);
            }
        }
        
        return locks;
    }
    
    /**
     * Generates a gap lock for the given index and conditions.
     */
    private Lock generateGapLock(Index index, String queryConditions, Lock.LockMode mode) {
        Lock gapLock = new Lock(index.getIndexName(), Lock.LockGranularity.RANGE, mode);
        gapLock.addPredicate("GAP_LOCK");
        addPredicatesFromConditions(gapLock, queryConditions);
        return gapLock;
    }
    
    /**
     * Detects if two locks conflict with each other.
     * 
     * Lock compatibility matrix (InnoDB):
     * - SHARED + SHARED = Compatible
     * - SHARED + EXCLUSIVE = Conflict
     * - EXCLUSIVE + SHARED = Conflict
     * - EXCLUSIVE + EXCLUSIVE = Conflict
     * 
     * Additional rules:
     * - Locks on different indexes don't conflict
     * - Row locks conflict only if they target the same row (same predicates)
     * - Range locks conflict if their ranges overlap
     * - Table locks conflict with any lock on the same table
     * 
     * @param lock1 First lock
     * @param lock2 Second lock
     * @return true if locks conflict, false otherwise
     */
    public boolean detectLockConflict(Lock lock1, Lock lock2) {
        if (lock1 == null || lock2 == null) {
            return false;
        }
        
        // Locks on different indexes don't conflict (unless table locks)
        if (!lock1.getDatabaseIndex().equals(lock2.getDatabaseIndex())) {
            // Check if either is a table lock
            if (lock1.getLockGranularity() == Lock.LockGranularity.TABLE ||
                lock2.getLockGranularity() == Lock.LockGranularity.TABLE) {
                // Table locks conflict with any lock on the same table
                String table1 = extractTableName(lock1.getDatabaseIndex());
                String table2 = extractTableName(lock2.getDatabaseIndex());
                if (!table1.equals(table2)) {
                    return false;
                }
                // Same table - continue to check lock mode compatibility
            } else {
                return false;
            }
        }
        
        // Check lock mode compatibility
        if (lock1.getLockMode() == Lock.LockMode.SHARED && 
            lock2.getLockMode() == Lock.LockMode.SHARED) {
            // Shared locks are compatible
            return false;
        }
        
        // At least one lock is EXCLUSIVE, check granularity and predicates
        Lock.LockGranularity gran1 = lock1.getLockGranularity();
        Lock.LockGranularity gran2 = lock2.getLockGranularity();
        
        // Table locks conflict with everything on the same table
        if (gran1 == Lock.LockGranularity.TABLE || gran2 == Lock.LockGranularity.TABLE) {
            return true;
        }
        
        // Row locks: conflict only if same row (same predicates)
        if (gran1 == Lock.LockGranularity.ROW && gran2 == Lock.LockGranularity.ROW) {
            return predicatesOverlap(lock1.getPredicates(), lock2.getPredicates());
        }
        
        // Range locks: conflict if ranges overlap
        if (gran1 == Lock.LockGranularity.RANGE || gran2 == Lock.LockGranularity.RANGE) {
            return rangesOverlap(lock1.getPredicates(), lock2.getPredicates());
        }
        
        // Default: assume conflict if we can't determine otherwise
        return true;
    }
    
    /**
     * Checks if query conditions represent an exact match on a unique index.
     */
    private boolean hasExactMatch(String queryConditions, Index index) {
        if (queryConditions == null || !index.isUnique()) {
            return false;
        }
        
        // Check if all index columns are present with equality conditions
        Set<String> conditionColumns = extractColumnsFromConditions(queryConditions);
        Set<String> indexColumns = new HashSet<>();
        for (String col : index.getColumns()) {
            indexColumns.add(col.toLowerCase());
        }
        
        // For exact match, all index columns must be in conditions with equality
        if (!conditionColumns.containsAll(indexColumns)) {
            return false;
        }
        
        // Check for equality operators (not range operators)
        return !hasRangeCondition(queryConditions);
    }
    
    /**
     * Checks if query conditions contain range operators.
     */
    private boolean hasRangeCondition(String queryConditions) {
        if (queryConditions == null) {
            return false;
        }
        
        String conditions = queryConditions.toUpperCase();
        return conditions.contains(">") || 
               conditions.contains("<") || 
               conditions.contains("BETWEEN") ||
               conditions.contains("LIKE") ||
               conditions.contains("IN");
    }
    
    /**
     * Extracts column names from WHERE clause conditions.
     * Uses SQLParser for more robust parsing.
     */
    private Set<String> extractColumnsFromConditions(String conditions) {
        Set<String> columns = new HashSet<>();
        
        if (conditions == null) {
            return columns;
        }
        
        // Use SQLParser to extract columns
        List<String> columnList = sqlParser.extractColumnsFromWhere(conditions);
        columns.addAll(columnList);
        
        return columns;
    }
    
    /**
     * Adds predicates from query conditions to a lock.
     */
    private void addPredicatesFromConditions(Lock lock, String queryConditions) {
        if (queryConditions == null || queryConditions.isEmpty()) {
            return;
        }
        
        // Split by AND/OR and add each condition as a predicate
        String[] conditions = queryConditions.split("(?i)\\s+AND\\s+|\\s+OR\\s+");
        for (String condition : conditions) {
            String trimmed = condition.trim();
            if (!trimmed.isEmpty()) {
                lock.addPredicate(trimmed);
            }
        }
    }
    
    /**
     * Checks if two sets of predicates overlap (for row locks).
     */
    private boolean predicatesOverlap(List<String> predicates1, List<String> predicates2) {
        if (predicates1 == null || predicates2 == null) {
            return true; // Conservative: assume overlap if predicates unknown
        }
        
        if (predicates1.isEmpty() || predicates2.isEmpty()) {
            return true; // Conservative: assume overlap
        }
        
        // Normalize and compare predicates
        Set<String> normalized1 = normalizePredicates(predicates1);
        Set<String> normalized2 = normalizePredicates(predicates2);
        
        // Check for any common predicates
        for (String pred1 : normalized1) {
            for (String pred2 : normalized2) {
                if (predicatesMatch(pred1, pred2)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if two ranges overlap (for range locks).
     */
    private boolean rangesOverlap(List<String> predicates1, List<String> predicates2) {
        // For range locks, we conservatively assume they overlap
        // A more sophisticated implementation would parse range conditions
        // and check for actual range overlap
        
        if (predicates1 == null || predicates2 == null) {
            return true;
        }
        
        if (predicates1.isEmpty() || predicates2.isEmpty()) {
            return true;
        }
        
        // Check if both are gap locks - gap locks can conflict
        boolean isGap1 = predicates1.stream().anyMatch(p -> p.contains("GAP_LOCK"));
        boolean isGap2 = predicates2.stream().anyMatch(p -> p.contains("GAP_LOCK"));
        
        if (isGap1 && isGap2) {
            return true; // Gap locks can conflict
        }
        
        // Check for insert intention locks - they conflict with gap locks
        boolean isInsert1 = predicates1.stream().anyMatch(p -> p.contains("INSERT_INTENTION"));
        boolean isInsert2 = predicates2.stream().anyMatch(p -> p.contains("INSERT_INTENTION"));
        
        if ((isInsert1 && isGap2) || (isInsert2 && isGap1)) {
            return true; // Insert intention conflicts with gap lock
        }
        
        // Otherwise, assume ranges overlap (conservative)
        return true;
    }
    
    /**
     * Normalizes predicates for comparison.
     */
    private Set<String> normalizePredicates(List<String> predicates) {
        Set<String> normalized = new HashSet<>();
        for (String pred : predicates) {
            // Remove extra whitespace and convert to lowercase
            String norm = pred.trim().toLowerCase().replaceAll("\\s+", " ");
            normalized.add(norm);
        }
        return normalized;
    }
    
    /**
     * Checks if two predicates match (represent the same condition).
     */
    private boolean predicatesMatch(String pred1, String pred2) {
        // Simple string comparison after normalization
        return pred1.equals(pred2);
    }
    
    /**
     * Extracts table name from index name.
     */
    private String extractTableName(String indexName) {
        if (indexName.endsWith("_table")) {
            return indexName.substring(0, indexName.length() - 6);
        }
        // Assume index name format: tablename_indexname or just indexname
        int underscorePos = indexName.indexOf('_');
        if (underscorePos > 0) {
            return indexName.substring(0, underscorePos);
        }
        return indexName;
    }
    
    /**
     * Checks if a string is a common SQL keyword.
     */
    private boolean isSQLKeyword(String word) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "and", "or", "not", "null", "true", "false", "is", "in", "like", 
            "between", "exists", "all", "any", "some"
        ));
        return keywords.contains(word.toLowerCase());
    }
    
    /**
     * Sets the IndexAnalyzer to use for index inference.
     */
    public void setIndexAnalyzer(IndexAnalyzer indexAnalyzer) {
        this.indexAnalyzer = indexAnalyzer;
    }
    
    /**
     * Gets the IndexAnalyzer being used.
     */
    public IndexAnalyzer getIndexAnalyzer() {
        return indexAnalyzer;
    }
}
