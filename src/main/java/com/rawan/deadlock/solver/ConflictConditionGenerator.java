package com.rawan.deadlock.solver;

import com.microsoft.z3.*;
import com.rawan.deadlock.model.Lock;
import com.rawan.deadlock.model.SQLStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates Z3 SMT formulas for conflict conditions between SQL statements.
 * Implements conflict condition generation logic from WeSEER paper.
 */
public class ConflictConditionGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConflictConditionGenerator.class);
    
    private final Context ctx;
    private final Map<String, Expr> symbolTable;
    
    public ConflictConditionGenerator(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("Z3 Context cannot be null");
        }
        this.ctx = ctx;
        this.symbolTable = new HashMap<>();
    }
    
    /**
     * Generates a Z3 formula representing the conflict condition between two SQL statements.
     * A conflict occurs when:
     * 1. Both statements access the same data (same table and overlapping predicates)
     * 2. At least one is a write operation (UPDATE, INSERT, DELETE)
     * 
     * @param w Write statement (or first statement)
     * @param r Read statement (or second statement)
     * @return Z3 BoolExpr representing the conflict condition
     */
    public BoolExpr generateConflictCondition(SQLStatement w, SQLStatement r) {
        if (w == null || r == null) {
            throw new IllegalArgumentException("SQL statements cannot be null");
        }
        
        logger.debug("Generating conflict condition between {} and {}", w, r);
        
        // Check if statements access the same table
        if (w.getTableNames() == null || w.getTableNames().isEmpty() ||
            r.getTableNames() == null || r.getTableNames().isEmpty()) {
            return ctx.mkFalse();
        }
        
        // Check if there's any table overlap
        boolean hasOverlap = false;
        for (String table : w.getTableNames()) {
            if (r.getTableNames().contains(table)) {
                hasOverlap = true;
                break;
            }
        }
        
        if (!hasOverlap) {
            // Different tables - no conflict
            return ctx.mkFalse();
        }
        
        // Check if at least one is a write operation
        boolean hasWrite = isWriteOperation(w) || isWriteOperation(r);
        if (!hasWrite) {
            // Both are reads - no conflict (shared locks are compatible)
            return ctx.mkFalse();
        }
        
        // Generate predicate overlap condition
        BoolExpr predicateOverlap = generatePredicateOverlap(w, r);
        
        return predicateOverlap;
    }
    
    /**
     * Unifies query conditions from a SQL statement with symbolic result.
     * Converts SQL WHERE clause conditions into Z3 expressions.
     * 
     * @param stmt SQL statement
     * @param symbolicResult Symbolic result variable name
     * @return Z3 expression representing unified query conditions
     */
    public Expr unifyQueryConditions(SQLStatement stmt, String symbolicResult) {
        if (stmt == null) {
            throw new IllegalArgumentException("SQL statement cannot be null");
        }
        
        logger.debug("Unifying query conditions for statement: {}", stmt);
        
        String queryConditions = stmt.getQueryConditions();
        if (queryConditions == null || queryConditions.trim().isEmpty()) {
            // No conditions - return true
            return ctx.mkTrue();
        }
        
        // Parse query conditions and create Z3 expressions
        BoolExpr condition = parseQueryCondition(queryConditions, stmt);
        
        // If symbolic result is provided, create a variable for it
        if (symbolicResult != null && !symbolicResult.isEmpty()) {
            Expr resultVar = getOrCreateSymbol(symbolicResult, ctx.getIntSort());
            symbolTable.put(symbolicResult, resultVar);
        }
        
        return condition;
    }
    
    /**
     * Generates a Z3 formula for range conflict condition.
     * Range conflicts occur when two range locks overlap.
     * 
     * @param rangeLock Lock with range predicates
     * @return Z3 BoolExpr representing the range conflict condition
     */
    public BoolExpr generateRangeConflictCondition(Lock rangeLock) {
        if (rangeLock == null) {
            throw new IllegalArgumentException("Lock cannot be null");
        }
        
        if (rangeLock.getLockGranularity() != Lock.LockGranularity.RANGE) {
            logger.warn("Lock is not a range lock: {}", rangeLock);
            return ctx.mkFalse();
        }
        
        logger.debug("Generating range conflict condition for lock: {}", rangeLock);
        
        // Parse predicates from the lock
        if (rangeLock.getPredicates() == null || rangeLock.getPredicates().isEmpty()) {
            // No predicates - assume full range
            return ctx.mkTrue();
        }
        
        BoolExpr rangeCondition = ctx.mkTrue();
        
        for (String predicate : rangeLock.getPredicates()) {
            BoolExpr predicateExpr = parseRangePredicate(predicate);
            rangeCondition = ctx.mkAnd(rangeCondition, predicateExpr);
        }
        
        return rangeCondition;
    }
    
    /**
     * Generates a Z3 formula for checking if two ranges overlap.
     * 
     * @param lock1 First range lock
     * @param lock2 Second range lock
     * @return Z3 BoolExpr representing whether ranges overlap
     */
    public BoolExpr generateRangeOverlapCondition(Lock lock1, Lock lock2) {
        if (lock1 == null || lock2 == null) {
            throw new IllegalArgumentException("Locks cannot be null");
        }
        
        BoolExpr range1 = generateRangeConflictCondition(lock1);
        BoolExpr range2 = generateRangeConflictCondition(lock2);
        
        // Ranges overlap if there exists a value that satisfies both conditions
        // This is represented by the conjunction of both range conditions
        return ctx.mkAnd(range1, range2);
    }
    
    // Helper methods
    
    private boolean isWriteOperation(SQLStatement stmt) {
        SQLStatement.StatementType type = stmt.getStatementType();
        return type == SQLStatement.StatementType.UPDATE ||
               type == SQLStatement.StatementType.INSERT ||
               type == SQLStatement.StatementType.DELETE;
    }
    
    private BoolExpr generatePredicateOverlap(SQLStatement stmt1, SQLStatement stmt2) {
        String cond1 = stmt1.getQueryConditions();
        String cond2 = stmt2.getQueryConditions();
        
        if (cond1 == null || cond1.trim().isEmpty()) {
            cond1 = "true";
        }
        if (cond2 == null || cond2.trim().isEmpty()) {
            cond2 = "true";
        }
        
        // Parse both conditions
        BoolExpr expr1 = parseQueryCondition(cond1, stmt1);
        BoolExpr expr2 = parseQueryCondition(cond2, stmt2);
        
        // Predicates overlap if there exists a value satisfying both
        return ctx.mkAnd(expr1, expr2);
    }
    
    private BoolExpr parseQueryCondition(String condition, SQLStatement stmt) {
        if (condition == null || condition.trim().isEmpty() || condition.trim().equalsIgnoreCase("true")) {
            return ctx.mkTrue();
        }
        
        // Simple parser for common SQL conditions
        condition = condition.trim();
        
        // Handle equality: "column = value"
        if (condition.contains("=") && !condition.contains(">") && !condition.contains("<")) {
            return parseEqualityCondition(condition, stmt);
        }
        
        // Handle range conditions: "column > value", "column < value", "column >= value", "column <= value"
        if (condition.contains(">") || condition.contains("<")) {
            return parseRangeCondition(condition, stmt);
        }
        
        // Handle BETWEEN: "column BETWEEN value1 AND value2"
        if (condition.toUpperCase().matches(".*\\bBETWEEN\\b.*")) {
            return parseBetweenCondition(condition, stmt);
        }
        
        // Handle IN: "column IN (value1, value2, ...)"
        if (condition.toUpperCase().matches(".*\\bIN\\b.*\\(.*\\).*")) {
            return parseInCondition(condition, stmt);
        }
        
        // Handle LIKE: "column LIKE pattern"
        if (condition.toUpperCase().matches(".*\\bLIKE\\b.*")) {
            return parseLikeCondition(condition, stmt);
        }
        
        // Handle AND/OR
        if (condition.toUpperCase().matches(".*\\bAND\\b.*")) {
            return parseAndCondition(condition, stmt);
        }
        if (condition.toUpperCase().matches(".*\\bOR\\b.*")) {
            return parseOrCondition(condition, stmt);
        }
        
        // Default: return true (conservative - assume overlap)
        logger.warn("Unable to parse condition: {}", condition);
        return ctx.mkTrue();
    }
    
    private BoolExpr parseEqualityCondition(String condition, SQLStatement stmt) {
        String[] parts = condition.split("=");
        if (parts.length != 2) {
            return ctx.mkTrue();
        }
        
        String column = parts[0].trim();
        String value = parts[1].trim();
        
        // 1. If value starts with ' or " → use StringSort
        if (value.startsWith("'") || value.startsWith("\"")) {
            String stringValue = value.replace("'", "").replace("\"", "");
            Expr stringColumnExpr = getOrCreateSymbol(column, ctx.getStringSort());
            return ctx.mkEq(stringColumnExpr, ctx.mkString(stringValue));
        }
        
        // 2. Try Integer.parseInt() → use IntSort
        try {
            int intValue = Integer.parseInt(value);
            Expr columnExpr = getOrCreateSymbol(column, ctx.getIntSort());
            return ctx.mkEq(columnExpr, ctx.mkInt(intValue));
        } catch (NumberFormatException e) {
            // 3. Fall back to StringSort
            Expr stringColumnExpr = getOrCreateSymbol(column, ctx.getStringSort());
            return ctx.mkEq(stringColumnExpr, ctx.mkString(value));
        }
    }
    
    private BoolExpr parseRangeCondition(String condition, SQLStatement stmt) {
        condition = condition.trim();
        
        String column;
        String operator;
        String value;
        
        if (condition.contains(">=")) {
            String[] parts = condition.split(">=");
            column = parts[0].trim();
            operator = ">=";
            value = parts[1].trim();
        } else if (condition.contains("<=")) {
            String[] parts = condition.split("<=");
            column = parts[0].trim();
            operator = "<=";
            value = parts[1].trim();
        } else if (condition.contains(">")) {
            String[] parts = condition.split(">");
            column = parts[0].trim();
            operator = ">";
            value = parts[1].trim();
        } else if (condition.contains("<")) {
            String[] parts = condition.split("<");
            column = parts[0].trim();
            operator = "<";
            value = parts[1].trim();
        } else {
            return ctx.mkTrue();
        }
        
        // Value-first sort detection
        String cleanValue = value.replace("'", "").replace("\"", "");
        if (value.startsWith("'") || value.startsWith("\"")) {
            // String value - range comparisons not meaningful, return true
            return ctx.mkTrue();
        }
        
        try {
            int intValue = Integer.parseInt(cleanValue);
            Expr columnExpr = getOrCreateSymbol(column, ctx.getIntSort());
            IntExpr valueExpr = ctx.mkInt(intValue);
            
            switch (operator) {
                case ">":
                    return ctx.mkGt((ArithExpr) columnExpr, valueExpr);
                case ">=":
                    return ctx.mkGe((ArithExpr) columnExpr, valueExpr);
                case "<":
                    return ctx.mkLt((ArithExpr) columnExpr, valueExpr);
                case "<=":
                    return ctx.mkLe((ArithExpr) columnExpr, valueExpr);
                default:
                    return ctx.mkTrue();
            }
        } catch (NumberFormatException e) {
            // Fall back to StringSort
            return ctx.mkTrue();
        }
    }
    
    private BoolExpr parseBetweenCondition(String condition, SQLStatement stmt) {
        // Format: "column BETWEEN value1 AND value2"
        String[] parts = condition.split("(?i)BETWEEN");
        if (parts.length != 2) {
            return ctx.mkTrue();
        }
        
        String column = parts[0].trim();
        String rangePart = parts[1].trim();
        
        String[] rangeValues = rangePart.split("(?i)AND");
        if (rangeValues.length != 2) {
            return ctx.mkTrue();
        }
        
        String value1 = rangeValues[0].trim();
        String value2 = rangeValues[1].trim();
        
        // Value-first sort detection
        if (value1.startsWith("'") || value1.startsWith("\"") || value2.startsWith("'") || value2.startsWith("\"")) {
            // String values - BETWEEN not meaningful for strings in Z3
            return ctx.mkTrue();
        }
        
        try {
            int intValue1 = Integer.parseInt(value1);
            int intValue2 = Integer.parseInt(value2);
            
            Expr columnExpr = getOrCreateSymbol(column, ctx.getIntSort());
            BoolExpr ge = ctx.mkGe((ArithExpr) columnExpr, ctx.mkInt(intValue1));
            BoolExpr le = ctx.mkLe((ArithExpr) columnExpr, ctx.mkInt(intValue2));
            
            return ctx.mkAnd(ge, le);
        } catch (NumberFormatException e) {
            // Fall back - not parseable as int
            return ctx.mkTrue();
        }
    }
    
    private BoolExpr parseInCondition(String condition, SQLStatement stmt) {
        // Format: "column IN (value1, value2, ...)"
        String[] parts = condition.split("(?i)IN");
        if (parts.length != 2) {
            return ctx.mkTrue();
        }
        
        String column = parts[0].trim();
        String valuesPart = parts[1].trim();
        
        // Remove parentheses
        valuesPart = valuesPart.replaceAll("[()]", "").trim();
        String[] values = valuesPart.split(",");
        
        // Determine sort from the FIRST non-empty value
        boolean useStringSort = false;
        for (String v : values) {
            String trimmed = v.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
                useStringSort = true;
            } else {
                try {
                    Integer.parseInt(trimmed);
                    useStringSort = false;
                } catch (NumberFormatException e) {
                    useStringSort = true;
                }
            }
            break;
        }
        
        Sort sort = useStringSort ? ctx.getStringSort() : ctx.getIntSort();
        Expr columnExpr = getOrCreateSymbol(column, sort);
        
        BoolExpr[] orClauses = new BoolExpr[values.length];
        for (int i = 0; i < values.length; i++) {
            String value = values[i].trim().replace("'", "").replace("\"", "");
            if (useStringSort) {
                orClauses[i] = ctx.mkEq(columnExpr, ctx.mkString(value));
            } else {
                try {
                    int intValue = Integer.parseInt(value);
                    orClauses[i] = ctx.mkEq(columnExpr, ctx.mkInt(intValue));
                } catch (NumberFormatException e) {
                    orClauses[i] = ctx.mkEq(columnExpr, ctx.mkInt(0));
                }
            }
        }
        
        return ctx.mkOr(orClauses);
    }
    
    private BoolExpr parseLikeCondition(String condition, SQLStatement stmt) {
        // LIKE is complex to model in Z3, so we conservatively return true (assume overlap)
        logger.debug("LIKE condition detected, conservatively assuming overlap: {}", condition);
        return ctx.mkTrue();
    }
    
    private BoolExpr parseAndCondition(String condition, SQLStatement stmt) {
        String[] parts = condition.split("(?i)\\s+AND\\s+");
        BoolExpr[] conjuncts = new BoolExpr[parts.length];
        
        for (int i = 0; i < parts.length; i++) {
            conjuncts[i] = parseQueryCondition(parts[i].trim(), stmt);
        }
        
        return ctx.mkAnd(conjuncts);
    }
    
    private BoolExpr parseOrCondition(String condition, SQLStatement stmt) {
        String[] parts = condition.split("(?i)\\s+OR\\s+");
        BoolExpr[] disjuncts = new BoolExpr[parts.length];
        
        for (int i = 0; i < parts.length; i++) {
            disjuncts[i] = parseQueryCondition(parts[i].trim(), stmt);
        }
        
        return ctx.mkOr(disjuncts);
    }
    
    private BoolExpr parseRangePredicate(String predicate) {
        // Similar to parseQueryCondition but without SQLStatement context
        if (predicate == null || predicate.trim().isEmpty()) {
            return ctx.mkTrue();
        }
        
        predicate = predicate.trim();
        
        // Handle range conditions
        if (predicate.contains(">=") || predicate.contains("<=") || 
            predicate.contains(">") || predicate.contains("<")) {
            
            String column;
            String operator;
            String value;
            
            if (predicate.contains(">=")) {
                String[] parts = predicate.split(">=");
                column = parts[0].trim();
                operator = ">=";
                value = parts[1].trim();
            } else if (predicate.contains("<=")) {
                String[] parts = predicate.split("<=");
                column = parts[0].trim();
                operator = "<=";
                value = parts[1].trim();
            } else if (predicate.contains(">")) {
                String[] parts = predicate.split(">");
                column = parts[0].trim();
                operator = ">";
                value = parts[1].trim();
            } else {
                String[] parts = predicate.split("<");
                column = parts[0].trim();
                operator = "<";
                value = parts[1].trim();
            }
            
            // Value-first sort detection for parseRangePredicate
            String cleanVal = value.replace("'", "").replace("\"", "");
            if (value.startsWith("'") || value.startsWith("\"")) {
                return ctx.mkTrue();
            }
            
            try {
                int intValue = Integer.parseInt(cleanVal);
                Expr columnExpr = getOrCreateSymbol(column, ctx.getIntSort());
                IntExpr valueExpr = ctx.mkInt(intValue);
                
                switch (operator) {
                    case ">":
                        return ctx.mkGt((ArithExpr) columnExpr, valueExpr);
                    case ">=":
                        return ctx.mkGe((ArithExpr) columnExpr, valueExpr);
                    case "<":
                        return ctx.mkLt((ArithExpr) columnExpr, valueExpr);
                    case "<=":
                        return ctx.mkLe((ArithExpr) columnExpr, valueExpr);
                    default:
                        return ctx.mkTrue();
                }
            } catch (NumberFormatException e) {
                return ctx.mkTrue();
            }
        }
        
        return ctx.mkTrue();
    }
    
    private Expr getOrCreateSymbol(String name, Sort sort) {
        String cacheKey = name + "_" + sort.getName();
        if (symbolTable.containsKey(cacheKey)) {
            return symbolTable.get(cacheKey);
        }
        
        Expr symbol = ctx.mkConst(name + "_" + sort.getName(), sort);
        symbolTable.put(cacheKey, symbol);
        return symbol;
    }
    
    /**
     * Gets the symbol table containing all created Z3 variables.
     * 
     * @return Map of symbol names to Z3 expressions
     */
    public Map<String, Expr> getSymbolTable() {
        return new HashMap<>(symbolTable);
    }
    
    /**
     * Clears the symbol table.
     */
    public void clearSymbolTable() {
        symbolTable.clear();
    }
}
