package com.rawan.deadlock.solver;

import com.microsoft.z3.*;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Verifies deadlock cycles using Z3 SMT solver.
 * Implements deadlock verification logic from WeSEER paper.
 */
public class DeadlockVerifier {
    
    private static final Logger logger = LoggerFactory.getLogger(DeadlockVerifier.class);
    
    private final Context ctx;
    private final ConflictConditionGenerator conflictGenerator;
    private final Map<String, Transaction> transactionMap;
    private final int timeoutSeconds;
    
    public DeadlockVerifier() {
        // Create Z3 context with default configuration
        this.ctx = new Context();
        this.conflictGenerator = new ConflictConditionGenerator(ctx);
        this.transactionMap = new HashMap<>();
        this.timeoutSeconds = 30;
    }
    
    public DeadlockVerifier(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("Z3 Context cannot be null");
        }
        this.ctx = ctx;
        this.conflictGenerator = new ConflictConditionGenerator(ctx);
        this.transactionMap = new HashMap<>();
        this.timeoutSeconds = 30;
    }
    
    public DeadlockVerifier(Context ctx, int timeoutSeconds) {
        if (ctx == null) {
            throw new IllegalArgumentException("Z3 Context cannot be null");
        }
        this.ctx = ctx;
        this.conflictGenerator = new ConflictConditionGenerator(ctx);
        this.transactionMap = new HashMap<>();
        this.timeoutSeconds = timeoutSeconds;
    }
    
    /**
     * Registers a transaction for deadlock verification.
     * This allows the verifier to access path conditions from transactions.
     * 
     * @param transaction Transaction to register
     */
    public void registerTransaction(Transaction transaction) {
        if (transaction == null || transaction.getTransactionId() == null) {
            throw new IllegalArgumentException("Transaction and transaction ID cannot be null");
        }
        transactionMap.put(transaction.getTransactionId(), transaction);
    }
    
    /**
     * Verifies if a cycle in the SC-Graph represents a real deadlock.
     * A cycle is a real deadlock if:
     * 1. All path conditions along the cycle are satisfiable (conjoinPathConditions)
     * 2. All conflict conditions between statements are satisfiable (conjoinConflictConditions)
     * 
     * @param cycle List of SQL statements forming a cycle
     * @return true if the cycle represents a real deadlock (SAT), false otherwise (UNSAT)
     */
    public boolean verifyDeadlockCycle(List<SQLStatement> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            logger.warn("Empty or null cycle provided");
            return false;
        }
        
        logger.info("Verifying deadlock cycle with {} statements", cycle.size());
        
        try {
            // Conjoin path conditions from all transactions in the cycle
            BoolExpr pathConditions = conjoinPathConditions(cycle);
            
            // Conjoin conflict conditions between consecutive statements in the cycle
            BoolExpr conflictConditions = conjoinConflictConditions(cycle);
            
            // Combine both conditions
            BoolExpr deadlockFormula = ctx.mkAnd(pathConditions, conflictConditions);
            
            logger.debug("Deadlock formula: {}", deadlockFormula);
            
            // Solve the formula
            Status result = solve(deadlockFormula);
            
            if (result == Status.UNKNOWN) {
                logger.warn("Deadlock cycle could not be verified due to timeout or solver limitation");
                return false;
            }
            
            boolean isRealDeadlock = (result == Status.SATISFIABLE);
            logger.info("Deadlock verification result: {} ({})", isRealDeadlock, result);
            
            return isRealDeadlock;
            
        } catch (Exception e) {
            logger.error("Error during deadlock verification", e);
            return false;
        }
    }
    
    /**
     * Conjoins path conditions from all transactions involved in the cycle.
     * Path conditions represent the symbolic execution constraints that must be satisfied
     * for the transactions to reach the statements in the cycle.
     * 
     * @param cycle List of SQL statements forming a cycle
     * @return Z3 BoolExpr representing the conjunction of all path conditions
     */
    public BoolExpr conjoinPathConditions(List<SQLStatement> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return ctx.mkTrue();
        }
        
        logger.debug("Conjoining path conditions for cycle");
        
        // Collect unique transactions involved in the cycle
        Set<String> transactionIds = new HashSet<>();
        for (SQLStatement stmt : cycle) {
            String txId = findTransactionId(stmt);
            if (txId != null) {
                transactionIds.add(txId);
            }
        }
        
        List<BoolExpr> pathConditionExprs = new ArrayList<>();
        
        // For each transaction, get its path conditions
        for (String txId : transactionIds) {
            Transaction tx = transactionMap.get(txId);
            if (tx != null && tx.getPathConditions() != null) {
                for (String pathCondition : tx.getPathConditions()) {
                    if (pathCondition != null && !pathCondition.trim().isEmpty()) {
                        BoolExpr expr = parsePathCondition(pathCondition);
                        pathConditionExprs.add(expr);
                    }
                }
            }
        }
        
        // If no path conditions found, return true (no constraints)
        if (pathConditionExprs.isEmpty()) {
            logger.debug("No path conditions found, returning true");
            return ctx.mkTrue();
        }
        
        // If only one path condition, return it directly
        if (pathConditionExprs.size() == 1) {
            return pathConditionExprs.get(0);
        }
        
        // Conjoin all path conditions
        BoolExpr result = ctx.mkAnd(pathConditionExprs.toArray(new BoolExpr[0]));
        logger.debug("Conjoined {} path conditions", pathConditionExprs.size());
        
        // Simplify the result to normalize true/false expressions
        result = (BoolExpr) result.simplify();
        
        return result;
    }
    
    /**
     * Conjoins conflict conditions between consecutive statements in the cycle.
     * Conflict conditions represent the data access conflicts that create dependencies
     * between transactions.
     * 
     * @param cycle List of SQL statements forming a cycle
     * @return Z3 BoolExpr representing the conjunction of all conflict conditions
     */
    public BoolExpr conjoinConflictConditions(List<SQLStatement> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return ctx.mkTrue();
        }
        
        logger.debug("Conjoining conflict conditions for cycle");
        
        List<BoolExpr> conflictExprs = new ArrayList<>();
        
        // Generate conflict conditions between consecutive statements in the cycle
        for (int i = 0; i < cycle.size(); i++) {
            SQLStatement current = cycle.get(i);
            SQLStatement next = cycle.get((i + 1) % cycle.size()); // Wrap around to form cycle
            
            // Skip S-edges: same-transaction pairs have no conflict to check
            String txCurrent = current.getTransactionId();
            String txNext    = next.getTransactionId();
            if (txCurrent != null && txCurrent.equals(txNext)) {
                continue;
            }
            
            // Only C-edges (cross-transaction pairs) get conflict conditions
            BoolExpr conflictCondition = conflictGenerator.generateConflictCondition(current, next);
            conflictExprs.add(conflictCondition);
        }
        
        // If no conflict conditions found, return true (conservative)
        if (conflictExprs.isEmpty()) {
            logger.debug("No conflict conditions found, returning true");
            return ctx.mkTrue();
        }
        
        // Conjoin all conflict conditions
        BoolExpr result = ctx.mkAnd(conflictExprs.toArray(new BoolExpr[0]));
        logger.debug("Conjoined {} conflict conditions", conflictExprs.size());
        
        return result;
    }
    
    /**
     * Solves a Z3 formula and returns the result.
     * 
     * @param formula Z3 BoolExpr to solve
     * @return Status.SATISFIABLE if SAT, Status.UNSATISFIABLE if UNSAT, Status.UNKNOWN otherwise
     */
    public Status solve(BoolExpr formula) {
        if (formula == null) {
            throw new IllegalArgumentException("Formula cannot be null");
        }
        
        logger.debug("Solving formula: {}", formula);
        
        Solver solver = ctx.mkSolver();
        
        // Apply timeout to prevent indefinite solving
        Params params = ctx.mkParams();
        params.add("timeout", (int)(timeoutSeconds * 1000)); // Z3 uses milliseconds
        solver.setParameters(params);
        
        solver.add(formula);
        
        Status status = solver.check();
        
        logger.debug("Solver result: {}", status);
        
        if (status == Status.SATISFIABLE) {
            Model model = solver.getModel();
            logger.debug("Model: {}", model);
        } else if (status == Status.UNSATISFIABLE) {
            logger.debug("Formula is unsatisfiable");
        } else {
            logger.warn("Solver returned UNKNOWN status");
        }
        
        return status;
    }
    
    /**
     * Verifies multiple cycles and returns which ones are real deadlocks.
     * 
     * @param cycles List of cycles to verify
     * @return List of cycles that are real deadlocks
     */
    public List<List<SQLStatement>> verifyMultipleCycles(List<List<SQLStatement>> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return new ArrayList<>();
        }
        
        logger.info("Verifying {} cycles", cycles.size());
        
        List<List<SQLStatement>> realDeadlocks = new ArrayList<>();
        
        for (int i = 0; i < cycles.size(); i++) {
            List<SQLStatement> cycle = cycles.get(i);
            logger.debug("Verifying cycle {}/{}", i + 1, cycles.size());
            
            if (verifyDeadlockCycle(cycle)) {
                realDeadlocks.add(cycle);
            }
        }
        
        logger.info("Found {} real deadlocks out of {} cycles", realDeadlocks.size(), cycles.size());
        
        return realDeadlocks;
    }
    
    // Helper methods
    
    private String findTransactionId(SQLStatement stmt) {
        return stmt.getTransactionId();
    }
    
    private BoolExpr parsePathCondition(String pathCondition) {
        if (pathCondition == null || pathCondition.trim().isEmpty()) {
            return ctx.mkTrue();
        }
        
        pathCondition = pathCondition.trim();
        
        // Handle simple boolean conditions
        if (pathCondition.equalsIgnoreCase("true")) {
            return ctx.mkTrue();
        }
        if (pathCondition.equalsIgnoreCase("false")) {
            return ctx.mkFalse();
        }
        
        // Parse common path condition formats
        // Format: "variable operator value" (e.g., "x > 0", "y = 5", "z <= 10")
        
        // Handle equality
        if (pathCondition.contains("==") || (pathCondition.contains("=") && !pathCondition.contains(">") && !pathCondition.contains("<"))) {
            return parseEqualityPathCondition(pathCondition);
        }
        
        // Handle inequality
        if (pathCondition.contains("!=") || pathCondition.contains("<>")) {
            return parseInequalityPathCondition(pathCondition);
        }
        
        // Handle comparison operators
        if (pathCondition.contains(">=") || pathCondition.contains("<=") || 
            pathCondition.contains(">") || pathCondition.contains("<")) {
            return parseComparisonPathCondition(pathCondition);
        }
        
        // Handle logical operators
        if (pathCondition.contains("&&") || pathCondition.toUpperCase().contains(" AND ")) {
            return parseAndPathCondition(pathCondition);
        }
        if (pathCondition.contains("||") || pathCondition.toUpperCase().contains(" OR ")) {
            return parseOrPathCondition(pathCondition);
        }
        if (pathCondition.startsWith("!") || pathCondition.toUpperCase().startsWith("NOT ")) {
            return parseNotPathCondition(pathCondition);
        }
        
        // Default: return true (conservative)
        logger.warn("Unable to parse path condition: {}", pathCondition);
        return ctx.mkTrue();
    }
    
    private BoolExpr parseEqualityPathCondition(String condition) {
        String[] parts;
        if (condition.contains("==")) {
            parts = condition.split("==");
        } else {
            parts = condition.split("=");
        }
        
        if (parts.length != 2) {
            return ctx.mkTrue();
        }
        
        String var = parts[0].trim();
        String value = parts[1].trim();
        
        try {
            int intValue = Integer.parseInt(value);
            IntExpr varExpr = ctx.mkIntConst(var);
            return ctx.mkEq(varExpr, ctx.mkInt(intValue));
        } catch (NumberFormatException e) {
            // Try as boolean
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                BoolExpr varExpr = ctx.mkBoolConst(var);
                return ctx.mkEq(varExpr, ctx.mkBool(Boolean.parseBoolean(value)));
            }
            // Try as string
            SeqExpr varExpr = ctx.mkString(var);
            return ctx.mkEq(varExpr, ctx.mkString(value.replace("'", "").replace("\"", "")));
        }
    }
    
    private BoolExpr parseInequalityPathCondition(String condition) {
        String[] parts;
        if (condition.contains("!=")) {
            parts = condition.split("!=");
        } else {
            parts = condition.split("<>");
        }
        
        if (parts.length != 2) {
            return ctx.mkTrue();
        }
        
        String var = parts[0].trim();
        String value = parts[1].trim();
        
        try {
            int intValue = Integer.parseInt(value);
            IntExpr varExpr = ctx.mkIntConst(var);
            return ctx.mkNot(ctx.mkEq(varExpr, ctx.mkInt(intValue)));
        } catch (NumberFormatException e) {
            // Try as boolean
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                BoolExpr varExpr = ctx.mkBoolConst(var);
                return ctx.mkNot(ctx.mkEq(varExpr, ctx.mkBool(Boolean.parseBoolean(value))));
            }
            // Try as string
            SeqExpr varExpr = ctx.mkString(var);
            return ctx.mkNot(ctx.mkEq(varExpr, ctx.mkString(value.replace("'", "").replace("\"", ""))));
        }
    }
    
    private BoolExpr parseComparisonPathCondition(String condition) {
        String var;
        String operator;
        String value;
        
        if (condition.contains(">=")) {
            String[] parts = condition.split(">=");
            var = parts[0].trim();
            operator = ">=";
            value = parts[1].trim();
        } else if (condition.contains("<=")) {
            String[] parts = condition.split("<=");
            var = parts[0].trim();
            operator = "<=";
            value = parts[1].trim();
        } else if (condition.contains(">")) {
            String[] parts = condition.split(">");
            var = parts[0].trim();
            operator = ">";
            value = parts[1].trim();
        } else if (condition.contains("<")) {
            String[] parts = condition.split("<");
            var = parts[0].trim();
            operator = "<";
            value = parts[1].trim();
        } else {
            return ctx.mkTrue();
        }
        
        try {
            int intValue = Integer.parseInt(value);
            IntExpr varExpr = ctx.mkIntConst(var);
            IntExpr valueExpr = ctx.mkInt(intValue);
            
            switch (operator) {
                case ">":
                    return ctx.mkGt(varExpr, valueExpr);
                case ">=":
                    return ctx.mkGe(varExpr, valueExpr);
                case "<":
                    return ctx.mkLt(varExpr, valueExpr);
                case "<=":
                    return ctx.mkLe(varExpr, valueExpr);
                default:
                    return ctx.mkTrue();
            }
        } catch (NumberFormatException e) {
            logger.warn("Unable to parse comparison value as integer: {}", value);
            return ctx.mkTrue();
        }
    }
    
    private BoolExpr parseAndPathCondition(String condition) {
        String[] parts;
        if (condition.contains("&&")) {
            parts = condition.split("&&");
        } else {
            parts = condition.split("(?i)\\s+AND\\s+");
        }
        
        BoolExpr[] conjuncts = new BoolExpr[parts.length];
        for (int i = 0; i < parts.length; i++) {
            conjuncts[i] = parsePathCondition(parts[i].trim());
        }
        
        return ctx.mkAnd(conjuncts);
    }
    
    private BoolExpr parseOrPathCondition(String condition) {
        String[] parts;
        if (condition.contains("||")) {
            parts = condition.split("\\|\\|");
        } else {
            parts = condition.split("(?i)\\s+OR\\s+");
        }
        
        BoolExpr[] disjuncts = new BoolExpr[parts.length];
        for (int i = 0; i < parts.length; i++) {
            disjuncts[i] = parsePathCondition(parts[i].trim());
        }
        
        return ctx.mkOr(disjuncts);
    }
    
    private BoolExpr parseNotPathCondition(String condition) {
        String innerCondition;
        if (condition.startsWith("!")) {
            innerCondition = condition.substring(1).trim();
        } else {
            innerCondition = condition.substring(3).trim(); // Remove "NOT"
        }
        
        BoolExpr inner = parsePathCondition(innerCondition);
        return ctx.mkNot(inner);
    }
    
    /**
     * Gets the Z3 context used by this verifier.
     * 
     * @return Z3 Context
     */
    public Context getContext() {
        return ctx;
    }
    
    /**
     * Gets the conflict condition generator.
     * 
     * @return ConflictConditionGenerator
     */
    public ConflictConditionGenerator getConflictGenerator() {
        return conflictGenerator;
    }
    
    /**
     * Clears all registered transactions.
     */
    public void clearTransactions() {
        transactionMap.clear();
    }
    
    /**
     * Closes resources.
     * Note: The Z3 context is not closed here as it may be shared.
     * The owner of the context should close it.
     */
    public void close() {
        // Context is owned by the caller, don't close it here
        // Just clear internal state if needed
        clearTransactions();
    }
}
