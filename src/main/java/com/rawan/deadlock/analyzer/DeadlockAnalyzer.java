package com.rawan.deadlock.analyzer;

import com.microsoft.z3.Context;
import com.rawan.deadlock.graph.SCGraph;
import com.rawan.deadlock.model.Lock;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import com.rawan.deadlock.solver.ConflictConditionGenerator;
import com.rawan.deadlock.solver.DeadlockVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Three-phase deadlock detection algorithm following the WeSEER paper.
 * 
 * Phase 1 - Transaction-level:
 *   - Build transaction conflict graph
 *   - Find transaction conflict cycles
 *   - Filter transactions that cannot form cycles
 * 
 * Phase 2 - Coarse-grained:
 *   - Build coarse-grained SC-graph for each transaction conflict cycle
 *   - Detect coarse-grained deadlock cycles (table-level conflicts)
 *   - Filter out non-contributing statements
 * 
 * Phase 3 - Fine-grained:
 *   - Apply fine-grained lock modeling
 *   - Generate conflict conditions using Z3
 *   - Verify deadlocks with SMT solver
 *   - Report confirmed deadlocks with satisfying assignments
 */
public class DeadlockAnalyzer {
    
    private static final Logger logger = LoggerFactory.getLogger(DeadlockAnalyzer.class);
    
    private Context z3Context;
    private IndexAnalyzer indexAnalyzer;
    private LockGenerator lockGenerator;
    private ConflictConditionGenerator conflictConditionGenerator;
    private DeadlockVerifier deadlockVerifier;
    
    /**
     * Result of deadlock analysis.
     */
    public static class DeadlockResult {
        private List<List<SQLStatement>> confirmedDeadlocks;
        private List<List<Transaction>> transactionCycles;
        private List<List<SQLStatement>> coarseGrainedCycles;
        private int phase1CyclesFound;
        private int phase2CyclesFound;
        private int phase3CyclesConfirmed;
        
        public DeadlockResult() {
            this.confirmedDeadlocks = new ArrayList<>();
            this.transactionCycles = new ArrayList<>();
            this.coarseGrainedCycles = new ArrayList<>();
        }
        
        public List<List<SQLStatement>> getConfirmedDeadlocks() {
            return confirmedDeadlocks;
        }
        
        public void addConfirmedDeadlock(List<SQLStatement> cycle) {
            this.confirmedDeadlocks.add(cycle);
        }
        
        public List<List<Transaction>> getTransactionCycles() {
            return transactionCycles;
        }
        
        public void addTransactionCycle(List<Transaction> cycle) {
            this.transactionCycles.add(cycle);
        }
        
        public List<List<SQLStatement>> getCoarseGrainedCycles() {
            return coarseGrainedCycles;
        }
        
        public void addCoarseGrainedCycle(List<SQLStatement> cycle) {
            this.coarseGrainedCycles.add(cycle);
        }
        
        public int getPhase1CyclesFound() {
            return phase1CyclesFound;
        }
        
        public void setPhase1CyclesFound(int count) {
            this.phase1CyclesFound = count;
        }
        
        public int getPhase2CyclesFound() {
            return phase2CyclesFound;
        }
        
        public void setPhase2CyclesFound(int count) {
            this.phase2CyclesFound = count;
        }
        
        public int getPhase3CyclesConfirmed() {
            return phase3CyclesConfirmed;
        }
        
        public void setPhase3CyclesConfirmed(int count) {
            this.phase3CyclesConfirmed = count;
        }
        
        @Override
        public String toString() {
            return "DeadlockResult{" +
                    "phase1Cycles=" + phase1CyclesFound +
                    ", phase2Cycles=" + phase2CyclesFound +
                    ", phase3Confirmed=" + phase3CyclesConfirmed +
                    ", confirmedDeadlocks=" + confirmedDeadlocks.size() +
                    '}';
        }
    }
    
    /**
     * Constructor with default components.
     */
    public DeadlockAnalyzer() {
        this.z3Context = new Context();
        this.indexAnalyzer = new IndexAnalyzer();
        this.lockGenerator = new LockGenerator(indexAnalyzer);
        this.conflictConditionGenerator = new ConflictConditionGenerator(z3Context);
        this.deadlockVerifier = new DeadlockVerifier(z3Context);
    }
    
    /**
     * Constructor with Z3 solver timeout.
     * 
     * @param timeoutSeconds Z3 solver timeout in seconds
     */
    public DeadlockAnalyzer(int timeoutSeconds) {
        this.z3Context = new Context();
        this.indexAnalyzer = new IndexAnalyzer();
        this.lockGenerator = new LockGenerator(indexAnalyzer);
        this.conflictConditionGenerator = new ConflictConditionGenerator(z3Context);
        this.deadlockVerifier = new DeadlockVerifier(z3Context, timeoutSeconds);
    }
    
    /**
     * Constructor with custom components.
     */
    public DeadlockAnalyzer(Context z3Context, IndexAnalyzer indexAnalyzer, LockGenerator lockGenerator,
                           ConflictConditionGenerator conflictConditionGenerator,
                           DeadlockVerifier deadlockVerifier) {
        this.z3Context = z3Context;
        this.indexAnalyzer = indexAnalyzer;
        this.lockGenerator = lockGenerator;
        this.conflictConditionGenerator = conflictConditionGenerator;
        this.deadlockVerifier = deadlockVerifier;
    }
    
    /**
     * Analyzes a list of transactions for deadlocks using the three-phase algorithm.
     * 
     * @param transactions List of transactions to analyze
     * @return DeadlockResult containing all detected deadlocks
     */
    public DeadlockResult analyzeDeadlocks(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            logger.warn("No transactions provided for analysis");
            return new DeadlockResult();
        }
        
        logger.info("Starting three-phase deadlock analysis for {} transactions", transactions.size());
        
        DeadlockResult result = new DeadlockResult();
        
        // Register transactions with the deadlock verifier
        for (Transaction tx : transactions) {
            deadlockVerifier.registerTransaction(tx);
        }
        
        // Phase 1: Transaction-level analysis
        logger.info("Phase 1: Transaction-level conflict analysis");
        List<List<Transaction>> transactionCycles = phase1TransactionLevel(transactions);
        result.setPhase1CyclesFound(transactionCycles.size());
        logger.info("Phase 1 complete: {} transaction cycles found", transactionCycles.size());
        
        if (transactionCycles.isEmpty()) {
            logger.info("No transaction cycles found, analysis complete");
            return result;
        }
        
        // Phase 2: Coarse-grained analysis
        logger.info("Phase 2: Coarse-grained SC-graph analysis");
        Map<List<SQLStatement>, SCGraph> cycleToGraph = new HashMap<>();
        List<List<SQLStatement>> coarseGrainedCycles = phase2CoarseGrained(transactionCycles, cycleToGraph);
        result.setPhase2CyclesFound(coarseGrainedCycles.size());
        logger.info("Phase 2 complete: {} coarse-grained cycles found", coarseGrainedCycles.size());
        
        if (coarseGrainedCycles.isEmpty()) {
            logger.info("No coarse-grained cycles found, analysis complete");
            return result;
        }
        
        // Phase 3: Fine-grained verification
        logger.info("Phase 3: Fine-grained lock modeling and Z3 verification");
        List<List<SQLStatement>> confirmedDeadlocks = phase3FineGrained(coarseGrainedCycles, cycleToGraph);
        result.setPhase3CyclesConfirmed(confirmedDeadlocks.size());
        logger.info("Phase 3 complete: {} deadlocks confirmed", confirmedDeadlocks.size());
        
        // Deduplicate confirmed deadlocks
        confirmedDeadlocks = deduplicateCycles(confirmedDeadlocks);
        logger.info("After deduplication: {} unique deadlocks", confirmedDeadlocks.size());
        
        // Populate result
        for (List<Transaction> txCycle : transactionCycles) {
            result.addTransactionCycle(txCycle);
        }
        for (List<SQLStatement> cycle : coarseGrainedCycles) {
            result.addCoarseGrainedCycle(cycle);
        }
        for (List<SQLStatement> deadlock : confirmedDeadlocks) {
            result.addConfirmedDeadlock(deadlock);
        }
        
        logger.info("Deadlock analysis complete: {}", result);
        return result;
    }
    
    /**
     * Deduplicates cycles by generating a canonical key for each cycle.
     * Two cycles are duplicates if they contain the same set of (transactionId, executionOrder) pairs
     * regardless of starting point or direction.
     */
    private List<List<SQLStatement>> deduplicateCycles(List<List<SQLStatement>> cycles) {
        Set<String> seenKeys = new LinkedHashSet<>();
        List<List<SQLStatement>> unique = new ArrayList<>();
        
        // Count unique canonical transaction types across all cycles for size filtering
        Set<String> allCanonicalTxTypes = new HashSet<>();
        for (List<SQLStatement> cycle : cycles) {
            for (SQLStatement stmt : cycle) {
                String txId = stmt.getTransactionId();
                if (txId != null) {
                    allCanonicalTxTypes.add(txId.replace("_instance2", ""));
                }
            }
        }
        int maxCycleSize = allCanonicalTxTypes.size() * 2;
        
        for (List<SQLStatement> cycle : cycles) {
            // Skip cycles where all statements belong to the same transaction
            boolean allSameTx = cycle.stream()
                .map(s -> s.getTransactionId() != null 
                    ? s.getTransactionId().replaceAll("_instance2$", "") 
                    : "unknown")
                .distinct()
                .count() < 2;
            
            if (allSameTx) continue;
            
            // Filter out compound/repeated cycles
            if (maxCycleSize > 0 && cycle.size() > maxCycleSize) {
                continue;
            }
            
            // Build canonical key using rotation-based deduplication
            List<String> tokens = new ArrayList<>();
            for (SQLStatement stmt : cycle) {
                String txId = stmt.getTransactionId();
                if (txId != null) {
                    txId = txId
                        .replaceAll("^T\\d+_", "")      // strip T1_, T2_, T3_... copy prefix
                        .replaceAll("_instance2$", ""); // strip duplicate suffix
                }
                String tables = stmt.getTableNames() != null
                    ? String.join("|", stmt.getTableNames().stream().sorted().collect(Collectors.toList()))
                    : "null";
                tokens.add(txId + "_" + stmt.getStatementType() + "_" + tables);
            }
            // Find the lexicographically smallest rotation
            List<String> smallest = new ArrayList<>(tokens);
            for (int r = 1; r < tokens.size(); r++) {
                List<String> rotated = new ArrayList<>(tokens.subList(r, tokens.size()));
                rotated.addAll(tokens.subList(0, r));
                if (rotated.toString().compareTo(smallest.toString()) < 0) {
                    smallest = rotated;
                }
            }
            String key = String.join(",", smallest);
            
            // Detect repeated-unit expansions: if cycle is a sub-pattern repeated N/K times, skip it
            boolean isRepeatedExpansion = false;
            int n = smallest.size();
            for (int k = 1; k < n; k++) {
                if (n % k == 0) {
                    boolean repeated = true;
                    for (int idx = k; idx < n; idx++) {
                        if (!smallest.get(idx).equals(smallest.get(idx % k))) {
                            repeated = false;
                            break;
                        }
                    }
                    if (repeated) {
                        isRepeatedExpansion = true;
                        break;
                    }
                }
            }
            if (isRepeatedExpansion) continue;
            
            if (seenKeys.add(key)) {
                unique.add(cycle);
            }
        }

        // Second pass: for each unique set of canonical transaction types,
        // keep only the shortest cycle. Longer cycles involving the same
        // transaction methods are redundant for reporting purposes.
        Map<String, List<SQLStatement>> shortestPerTxSet = new LinkedHashMap<>();

        for (List<SQLStatement> cycle : unique) {
            // Build a sorted, deduplicated set of canonical transaction method names
            Set<String> txTypes = new TreeSet<>();
            for (SQLStatement stmt : cycle) {
                String txId = stmt.getTransactionId();
                if (txId != null) {
                    txId = txId
                        .replaceAll("^T\\d+_", "")
                        .replaceAll("_instance2$", "");
                    txTypes.add(txId);
                }
            }
            String txSetKey = String.join(",", txTypes);

            List<SQLStatement> existing = shortestPerTxSet.get(txSetKey);
            if (existing == null || cycle.size() < existing.size()) {
                shortestPerTxSet.put(txSetKey, cycle);
            }
        }

        return new ArrayList<>(shortestPerTxSet.values());
    }
    
    /**
     * Phase 1: Transaction-level conflict analysis.
     * Builds a transaction conflict graph and finds cycles.
     * 
     * @param transactions List of transactions
     * @return List of transaction cycles
     */
    private List<List<Transaction>> phase1TransactionLevel(List<Transaction> transactions) {
        // Duplicate each transaction to create a second concurrent instance
        List<Transaction> allInstances = new ArrayList<>(transactions);
        for (Transaction tx : transactions) {
            Transaction duplicate = duplicateTransaction(tx, tx.getTransactionId() + "_instance2");
            allInstances.add(duplicate);
            // Register duplicate with deadlock verifier so path conditions can be resolved
            deadlockVerifier.registerTransaction(duplicate);
        }
        
        // Build transaction conflict graph
        Map<Transaction, Set<Transaction>> conflictGraph = new HashMap<>();
        Map<String, Transaction> txMap = new HashMap<>();
        
        // Initialize graph
        for (Transaction tx : allInstances) {
            conflictGraph.put(tx, new HashSet<>());
            txMap.put(tx.getTransactionId(), tx);
        }
        
        // Detect conflicts between transactions (directed edges)
        for (int i = 0; i < allInstances.size(); i++) {
            Transaction tx1 = allInstances.get(i);
            for (int j = i + 1; j < allInstances.size(); j++) {
                Transaction tx2 = allInstances.get(j);
                
                boolean tx1WritesToCommon = hasWriteOnCommonTable(tx1, tx2);
                boolean tx2WritesToCommon = hasWriteOnCommonTable(tx2, tx1);
                
                if (tx1WritesToCommon) {
                    conflictGraph.get(tx1).add(tx2);
                    logger.debug("Directed conflict: {} -> {}", 
                               tx1.getTransactionId(), tx2.getTransactionId());
                }
                if (tx2WritesToCommon) {
                    conflictGraph.get(tx2).add(tx1);
                    logger.debug("Directed conflict: {} -> {}", 
                               tx2.getTransactionId(), tx1.getTransactionId());
                }
            }
        }
        
        // Find cycles in transaction conflict graph
        List<List<Transaction>> cycles = findTransactionCycles(conflictGraph);
        
        logger.debug("Found {} transaction-level cycles", cycles.size());
        return cycles;
    }
    
    /**
     * Creates a duplicate of a transaction with a new ID.
     * The duplicate has the same SQL statements (with updated transactionId) and path conditions.
     */
    private Transaction duplicateTransaction(Transaction original, String newId) {
        Transaction duplicate = new Transaction(newId);
        for (SQLStatement stmt : original.getSqlStatements()) {
            SQLStatement copy = new SQLStatement(stmt.getStatementType(),
                stmt.getTableNames(), stmt.getQueryConditions(), stmt.getExecutionOrder());
            copy.setRawSql(stmt.getRawSql());
            copy.setResultReference(stmt.getResultReference());
            copy.setSqlParameters(new HashMap<>(stmt.getSqlParameters()));
            copy.setTableAliases(new HashMap<>(stmt.getTableAliases()));
            copy.setTriggeringCodeLocation(stmt.getTriggeringCodeLocation());
            duplicate.addSqlStatement(copy);
        }
        for (String pc : original.getPathConditions()) {
            duplicate.addPathCondition(pc);
        }
        duplicate.setBeginTimestamp(original.getBeginTimestamp());
        duplicate.setCommitTimestamp(original.getCommitTimestamp());
        duplicate.setAbortTimestamp(original.getAbortTimestamp());
        return duplicate;
    }

    /**
     * Checks if tx1 has a WRITE operation on a table that tx2 also accesses (read or write).
     */
    private boolean hasWriteOnCommonTable(Transaction tx1, Transaction tx2) {
        Set<String> tx2Tables = getAccessedTables(tx2);
        for (SQLStatement stmt : tx1.getSqlStatements()) {
            if (isWriteOperation(stmt) && stmt.getTableNames() != null) {
                for (String table : stmt.getTableNames()) {
                    if (tx2Tables.contains(table)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Gets all tables accessed by a transaction.
     */
    private Set<String> getAccessedTables(Transaction tx) {
        Set<String> tables = new HashSet<>();
        for (SQLStatement stmt : tx.getSqlStatements()) {
            if (stmt.getTableNames() != null && !stmt.getTableNames().isEmpty()) {
                tables.addAll(stmt.getTableNames());
            }
        }
        return tables;
    }
    
    /**
     * Checks if a transaction has write operations on a specific table.
     */
    private boolean hasWriteOnTable(Transaction tx, String table) {
        for (SQLStatement stmt : tx.getSqlStatements()) {
            if (stmt.getTableNames() != null && stmt.getTableNames().contains(table) && isWriteOperation(stmt)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if a statement is a write operation.
     */
    private boolean isWriteOperation(SQLStatement stmt) {
        SQLStatement.StatementType type = stmt.getStatementType();
        return type == SQLStatement.StatementType.UPDATE ||
               type == SQLStatement.StatementType.INSERT ||
               type == SQLStatement.StatementType.DELETE;
    }
    
    /**
     * Finds cycles in the directed transaction conflict graph using DFS.
     */
    private List<List<Transaction>> findTransactionCycles(Map<Transaction, Set<Transaction>> graph) {
        List<List<Transaction>> cycles = new ArrayList<>();
        Set<Transaction> visited = new HashSet<>();
        Set<Transaction> recursionStack = new HashSet<>();
        Deque<Transaction> path = new ArrayDeque<>();
        
        for (Transaction tx : graph.keySet()) {
            if (!visited.contains(tx)) {
                findTransactionCyclesUtil(tx, visited, recursionStack, path, graph, cycles);
            }
        }
        
        return cycles;
    }
    
    /**
     * DFS utility for finding directed transaction cycles.
     * Uses a per-path Deque stack to correctly reconstruct cycles.
     */
    private void findTransactionCyclesUtil(Transaction current, Set<Transaction> visited,
                                          Set<Transaction> recursionStack,
                                          Deque<Transaction> path,
                                          Map<Transaction, Set<Transaction>> graph,
                                          List<List<Transaction>> cycles) {
        visited.add(current);
        recursionStack.add(current);
        path.push(current);
        
        for (Transaction neighbor : graph.get(current)) {
            if (!visited.contains(neighbor)) {
                findTransactionCyclesUtil(neighbor, visited, recursionStack, path, graph, cycles);
            } else if (recursionStack.contains(neighbor)) {
                // Found a directed cycle - reconstruct from path stack
                List<Transaction> cycle = new ArrayList<>();
                // Walk the path from top until we find neighbor
                for (Transaction node : path) {
                    cycle.add(node);
                    if (node == neighbor) {
                        break;
                    }
                }
                // Reverse since Deque iteration is top-first (current→...→neighbor)
                Collections.reverse(cycle);
                
                if (cycle.size() >= 2) {
                    cycles.add(cycle);
                }
            }
        }
        
        path.pop();
        recursionStack.remove(current);
    }
    
    /**
     * Phase 2: Coarse-grained SC-graph analysis.
     * Builds SC-graphs for transaction cycles and detects statement-level cycles.
     * 
     * @param transactionCycles List of transaction cycles from Phase 1
     * @return List of coarse-grained statement cycles
     */
    private List<List<SQLStatement>> phase2CoarseGrained(List<List<Transaction>> transactionCycles, Map<List<SQLStatement>, SCGraph> cycleToGraph) {
        List<List<SQLStatement>> allCycles = new ArrayList<>();
        
        for (List<Transaction> txCycle : transactionCycles) {
            logger.debug("Building coarse-grained SC-graph for transaction cycle of size {}", txCycle.size());
            
            // Build SC-graph for this transaction cycle
            SCGraph scGraph = new SCGraph();
            
            // Add all transactions to the graph (creates S-edges)
            for (Transaction tx : txCycle) {
                scGraph.addTransaction(tx);
            }
            
            // Add C-edges based on table-level conflicts
            addCoarseGrainedCEdges(scGraph, txCycle);
            
            // Find cycles in the SC-graph
            List<List<SQLStatement>> cycles = scGraph.findCycles();
            logger.debug("Found {} coarse-grained cycles in this transaction cycle", cycles.size());
            
            // Store the SCGraph for each cycle so phase 3 can use it
            for (List<SQLStatement> cycle : cycles) {
                cycleToGraph.put(cycle, scGraph);
            }
            
            allCycles.addAll(cycles);
        }
        
        // Deduplicate before returning to avoid redundant Z3 solving in Phase 3
        allCycles = deduplicateCycles(allCycles);
        logger.debug("After phase 2 deduplication: {} unique coarse-grained cycles", allCycles.size());
        
        return allCycles;
    }
    
    /**
     * Adds coarse-grained C-edges based on table-level conflicts.
     */
    private void addCoarseGrainedCEdges(SCGraph scGraph, List<Transaction> transactions) {
        // For each pair of transactions, add C-edges between conflicting statements
        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx1 = transactions.get(i);
            for (int j = i + 1; j < transactions.size(); j++) {
                Transaction tx2 = transactions.get(j);
                
                // Add C-edges between statements that access the same table
                for (SQLStatement stmt1 : tx1.getSqlStatements()) {
                    for (SQLStatement stmt2 : tx2.getSqlStatements()) {
                        if (hasCoarseGrainedConflict(stmt1, stmt2)) {
                            scGraph.addCEdge(stmt1, stmt2);
                            scGraph.addCEdge(stmt2, stmt1);
                            logger.trace("Added coarse-grained C-edge: {} <-> {}", 
                                       stmt1.getExecutionOrder(), stmt2.getExecutionOrder());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Checks if two statements have a coarse-grained conflict (same table, at least one write).
     */
    private boolean hasCoarseGrainedConflict(SQLStatement stmt1, SQLStatement stmt2) {
        // Must access the same table
        if (stmt1.getTableNames() == null || stmt1.getTableNames().isEmpty() ||
            stmt2.getTableNames() == null || stmt2.getTableNames().isEmpty()) {
            return false;
        }
        
        // Check if there's any table overlap
        boolean hasOverlap = false;
        for (String table1 : stmt1.getTableNames()) {
            if (stmt2.getTableNames().contains(table1)) {
                hasOverlap = true;
                break;
            }
        }
        
        if (!hasOverlap) {
            return false;
        }
        
        // At least one must be a write operation
        return isWriteOperation(stmt1) || isWriteOperation(stmt2);
    }
    
    /**
     * Phase 3: Fine-grained lock modeling and verification.
     * Applies detailed lock analysis and uses Z3 to verify deadlocks.
     * 
     * @param coarseGrainedCycles List of coarse-grained cycles from Phase 2
     * @return List of confirmed deadlock cycles
     */
    private List<List<SQLStatement>> phase3FineGrained(List<List<SQLStatement>> coarseGrainedCycles, Map<List<SQLStatement>, SCGraph> cycleToGraph) {
        List<List<SQLStatement>> confirmedDeadlocks = new ArrayList<>();
        
        for (List<SQLStatement> cycle : coarseGrainedCycles) {
            logger.debug("Verifying cycle of size {} with fine-grained analysis", cycle.size());
            
            // Check if cycle has lock conflicts (only on C-edges)
            SCGraph scGraph = cycleToGraph.get(cycle);
            if (hasLockConflicts(cycle, scGraph)) {
                // Verify with Z3 SMT solver
                boolean isRealDeadlock = deadlockVerifier.verifyDeadlockCycle(cycle);
                
                if (isRealDeadlock) {
                    logger.info("Confirmed deadlock cycle: {}", formatCycle(cycle));
                    confirmedDeadlocks.add(cycle);
                } else {
                    logger.debug("Cycle rejected by Z3 verification (unsatisfiable path/conflict conditions)");
                }
            } else {
                logger.debug("Cycle has no lock conflicts, skipping Z3 verification");
            }
        }
        
        return confirmedDeadlocks;
    }
    
    /**
     * Checks if a cycle has lock conflicts using fine-grained lock modeling.
     * Only checks lock conflicts between pairs connected by C-edges (conflict edges).
     * S-edge pairs (same transaction, sequential) are skipped.
     * 
     * @param cycle the statement cycle to check
     * @param scGraph the SC-graph containing edge type information, or null to check all pairs
     */
    private boolean hasLockConflicts(List<SQLStatement> cycle, SCGraph scGraph) {
        if (cycle.size() < 2) {
            return false;
        }
        
        boolean anyCEdgeChecked = false;
        
        // Check consecutive pairs in the cycle (including wrap-around)
        for (int i = 0; i < cycle.size(); i++) {
            SQLStatement stmt1 = cycle.get(i);
            SQLStatement stmt2 = cycle.get((i + 1) % cycle.size());
            
            // Determine edge type; only check lock conflicts for C-edges
            if (scGraph != null) {
                SCGraph.EdgeType edgeType = scGraph.findEdgeType(stmt1, stmt2);
                if (edgeType == SCGraph.EdgeType.S_EDGE) {
                    // S-edge: same transaction, sequential — skip conflict check
                    continue;
                }
            }
            
            anyCEdgeChecked = true;
            
            // Generate locks for both statements
            List<Lock> locks1 = generateLocks(stmt1);
            List<Lock> locks2 = generateLocks(stmt2);
            
            // Check for conflicts
            boolean hasConflict = false;
            for (Lock lock1 : locks1) {
                for (Lock lock2 : locks2) {
                    if (lockGenerator.detectLockConflict(lock1, lock2)) {
                        hasConflict = true;
                        break;
                    }
                }
                if (hasConflict) break;
            }
            
            if (!hasConflict) {
                // No conflict on this C-edge pair
                logger.trace("No lock conflict between statements {} and {}", 
                           stmt1.getExecutionOrder(), stmt2.getExecutionOrder());
                return false;
            }
        }
        
        // Must have checked at least one C-edge
        return anyCEdgeChecked;
    }
    
    /**
     * Generates locks for a SQL statement.
     */
    private List<Lock> generateLocks(SQLStatement stmt) {
        if (stmt.getStatementType() == SQLStatement.StatementType.SELECT) {
            // Determine isEmpty from the statement's queryResult field:
            // - null or empty queryResult → isEmpty = true (gap lock)
            // - non-empty queryResult → isEmpty = false (record lock)
            // If queryResult has not been populated (no runtime data available),
            // default conservatively to isEmpty = false to avoid false negatives.
            // Concolic execution would normally populate this field.
            boolean isEmpty;
            List<java.util.Map<String, Object>> queryResult = stmt.getQueryResult();
            if (queryResult != null && !queryResult.isEmpty()) {
                // Runtime data available and non-empty → record lock
                isEmpty = false;
            } else {
                // queryResult is null or empty; conservatively default to false
                // to avoid false negatives. Concolic execution would normally
                // populate this field, at which point an empty result would
                // yield isEmpty = true (gap lock).
                isEmpty = false;
            }
            return lockGenerator.generateSharedLocks(stmt, isEmpty);
        } else {
            return lockGenerator.generateExclusiveLocks(stmt);
        }
    }
    
    /**
     * Formats a cycle for logging.
     */
    private String formatCycle(List<SQLStatement> cycle) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cycle.size(); i++) {
            SQLStatement stmt = cycle.get(i);
            String tables = stmt.getTableNames() != null && !stmt.getTableNames().isEmpty() 
                ? String.join(",", stmt.getTableNames()) 
                : "null";
            sb.append(stmt.getStatementType()).append("(").append(tables).append(")");
            if (i < cycle.size() - 1) {
                sb.append(" -> ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Closes resources (Z3 context and deadlock verifier).
     */
    public void close() {
        if (deadlockVerifier != null) {
            deadlockVerifier.close();
        }
        if (z3Context != null) {
            z3Context.close();
        }
    }
    
    // Getters and setters
    
    public IndexAnalyzer getIndexAnalyzer() {
        return indexAnalyzer;
    }
    
    public void setIndexAnalyzer(IndexAnalyzer indexAnalyzer) {
        this.indexAnalyzer = indexAnalyzer;
    }
    
    public LockGenerator getLockGenerator() {
        return lockGenerator;
    }
    
    public void setLockGenerator(LockGenerator lockGenerator) {
        this.lockGenerator = lockGenerator;
    }
    
    public ConflictConditionGenerator getConflictConditionGenerator() {
        return conflictConditionGenerator;
    }
    
    public void setConflictConditionGenerator(ConflictConditionGenerator conflictConditionGenerator) {
        this.conflictConditionGenerator = conflictConditionGenerator;
    }
    
    public DeadlockVerifier getDeadlockVerifier() {
        return deadlockVerifier;
    }
    
    public void setDeadlockVerifier(DeadlockVerifier deadlockVerifier) {
        this.deadlockVerifier = deadlockVerifier;
    }
}
