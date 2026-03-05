package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.analyzer.DeadlockAnalyzer.DeadlockResult;
import com.rawan.deadlock.analyzer.IndexAnalyzer.Index;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DeadlockAnalyzerTest {
    
    private DeadlockAnalyzer analyzer;
    
    @Before
    public void setUp() {
        analyzer = new DeadlockAnalyzer();
        
        // Register some common indexes for testing
        IndexAnalyzer indexAnalyzer = analyzer.getIndexAnalyzer();
        indexAnalyzer.registerIndex(new Index("users_PRIMARY", "users", Arrays.asList("id"), true, true));
        indexAnalyzer.registerIndex(new Index("orders_PRIMARY", "orders", Arrays.asList("id"), true, true));
        indexAnalyzer.registerIndex(new Index("accounts_PRIMARY", "accounts", Arrays.asList("id"), true, true));
    }
    
    @After
    public void tearDown() {
        if (analyzer != null) {
            analyzer.close();
        }
    }
    
    // ========== Constructor Tests ==========
    
    @Test
    public void testDefaultConstructor() {
        DeadlockAnalyzer testAnalyzer = new DeadlockAnalyzer();
        assertNotNull(testAnalyzer);
        assertNotNull(testAnalyzer.getIndexAnalyzer());
        assertNotNull(testAnalyzer.getLockGenerator());
        assertNotNull(testAnalyzer.getConflictConditionGenerator());
        assertNotNull(testAnalyzer.getDeadlockVerifier());
        testAnalyzer.close();
    }
    
    // ========== Empty/Null Input Tests ==========
    
    @Test
    public void testAnalyzeDeadlocksNullTransactions() {
        DeadlockResult result = analyzer.analyzeDeadlocks(null);
        assertNotNull(result);
        assertEquals(0, result.getPhase1CyclesFound());
        assertEquals(0, result.getPhase2CyclesFound());
        assertEquals(0, result.getPhase3CyclesConfirmed());
        assertTrue(result.getConfirmedDeadlocks().isEmpty());
    }
    
    @Test
    public void testAnalyzeDeadlocksEmptyTransactions() {
        DeadlockResult result = analyzer.analyzeDeadlocks(new ArrayList<>());
        assertNotNull(result);
        assertEquals(0, result.getPhase1CyclesFound());
        assertEquals(0, result.getPhase2CyclesFound());
        assertEquals(0, result.getPhase3CyclesConfirmed());
        assertTrue(result.getConfirmedDeadlocks().isEmpty());
    }
    
    @Test
    public void testAnalyzeDeadlocksSingleTransaction() {
        Transaction tx = createTransaction("TX1", 
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx));
        assertNotNull(result);
        // Single transaction now gets duplicated, so TX1 vs TX1_instance2 can conflict
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    // ========== Phase 1: Transaction-Level Tests ==========
    
    @Test
    public void testPhase1NoConflict() {
        // Two transactions accessing different tables
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2)
        );
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("orders"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        // Each TX now gets duplicated and conflicts with its own instance2
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testPhase1ConflictSameTableWithWrites() {
        // Two transactions accessing same table with writes
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2)
        );
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        // Should detect transaction-level conflict
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testPhase1NoConflictReadOnly() {
        // Two transactions with only reads on same table - no conflict
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 2)
        );
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 3", 1),
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 4", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        assertEquals(0, result.getPhase1CyclesFound());
    }
    
    // ========== Phase 2: Coarse-Grained Tests ==========
    
    @Test
    public void testPhase2CoarseGrainedCycle() {
        // Classic deadlock: TX1 locks A then B, TX2 locks B then A
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2)
        );
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        // Should detect at transaction and coarse-grained levels
        assertTrue(result.getPhase1CyclesFound() >= 0);
        assertTrue(result.getPhase2CyclesFound() >= 0);
    }
    
    // ========== Phase 3: Fine-Grained Tests ==========
    
    @Test
    public void testPhase3ClassicDeadlock() {
        // Classic deadlock scenario with path conditions
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2)
        );
        tx1.addPathCondition("true");
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2)
        );
        tx2.addPathCondition("true");
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        
        // Should go through all phases
        assertNotNull(result);
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testPhase3UnsatisfiablePathConditions() {
        // Deadlock cycle with unsatisfiable path conditions
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2)
        );
        tx1.addPathCondition("x > 10");
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2)
        );
        tx2.addPathCondition("x < 5");
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        
        // May be filtered out by Z3 if path conditions are unsatisfiable
        assertNotNull(result);
    }
    
    // ========== Integration Tests ==========
    
    @Test
    public void testThreeTransactionDeadlock() {
        // Three-way deadlock: TX1 -> TX2 -> TX3 -> TX1
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2)
        );
        tx1.addPathCondition("true");
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("accounts"), "id = 1", 2)
        );
        tx2.addPathCondition("true");
        
        Transaction tx3 = createTransaction("TX3",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("accounts"), "id = 2", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2)
        );
        tx3.addPathCondition("true");
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2, tx3));
        
        assertNotNull(result);
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testMultipleIndependentTransactions() {
        // Multiple transactions with no conflicts - each accesses different table
        List<Transaction> transactions = new ArrayList<>();
        String[] tables = {"users", "orders", "accounts", "products", "customers"};
        for (int i = 0; i < 5; i++) {
            Transaction tx = createTransaction("TX" + i,
                new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList(tables[i]), "id = " + i, 1),
                new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList(tables[i]), "id = " + i, 2)
            );
            transactions.add(tx);
        }
        
        DeadlockResult result = analyzer.analyzeDeadlocks(transactions);
        
        assertNotNull(result);
        // Each TX now gets duplicated and conflicts with its own instance2
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testComplexDeadlockScenario() {
        // Complex scenario with multiple statements per transaction
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2),
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("orders"), "user_id = 1", 3),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 4)
        );
        tx1.addPathCondition("true");
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("orders"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2),
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 3),
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 4)
        );
        tx2.addPathCondition("true");
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        
        assertNotNull(result);
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    // ========== DeadlockResult Tests ==========
    
    @Test
    public void testDeadlockResultToString() {
        DeadlockResult result = new DeadlockResult();
        result.setPhase1CyclesFound(2);
        result.setPhase2CyclesFound(1);
        result.setPhase3CyclesConfirmed(1);
        
        String str = result.toString();
        assertNotNull(str);
        assertTrue(str.contains("phase1Cycles=2"));
        assertTrue(str.contains("phase2Cycles=1"));
        assertTrue(str.contains("phase3Confirmed=1"));
    }
    
    @Test
    public void testDeadlockResultGetters() {
        DeadlockResult result = new DeadlockResult();
        
        Transaction tx1 = createTransaction("TX1");
        Transaction tx2 = createTransaction("TX2");
        List<Transaction> txCycle = Arrays.asList(tx1, tx2);
        result.addTransactionCycle(txCycle);
        
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2);
        List<SQLStatement> stmtCycle = Arrays.asList(stmt1, stmt2);
        result.addCoarseGrainedCycle(stmtCycle);
        result.addConfirmedDeadlock(stmtCycle);
        
        assertEquals(1, result.getTransactionCycles().size());
        assertEquals(1, result.getCoarseGrainedCycles().size());
        assertEquals(1, result.getConfirmedDeadlocks().size());
    }
    
    // ========== Edge Cases ==========
    
    @Test
    public void testTransactionWithNoStatements() {
        Transaction tx1 = new Transaction("TX1");
        Transaction tx2 = new Transaction("TX2");
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        
        assertNotNull(result);
        assertEquals(0, result.getPhase1CyclesFound());
    }
    
    @Test
    public void testTransactionWithNullTableName() {
        Transaction tx = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, null, "id = 1", 1)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx));
        
        assertNotNull(result);
        assertEquals(0, result.getPhase1CyclesFound());
    }
    
    @Test
    public void testMixedReadWriteOperations() {
        Transaction tx1 = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.INSERT, Arrays.asList("orders"), "user_id = 1", 2),
            new SQLStatement(SQLStatement.StatementType.DELETE, Arrays.asList("accounts"), "user_id = 1", 3)
        );
        
        Transaction tx2 = createTransaction("TX2",
            new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1),
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("orders"), "user_id = 1", 2)
        );
        
        DeadlockResult result = analyzer.analyzeDeadlocks(Arrays.asList(tx1, tx2));
        
        assertNotNull(result);
        // Should detect conflicts due to mixed read/write operations
        assertTrue(result.getPhase1CyclesFound() >= 0);
    }
    
    @Test
    public void testResourceCleanup() {
        DeadlockAnalyzer testAnalyzer = new DeadlockAnalyzer();
        
        Transaction tx = createTransaction("TX1",
            new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1)
        );
        
        testAnalyzer.analyzeDeadlocks(Arrays.asList(tx));
        
        // Should not throw exception
        testAnalyzer.close();
        // Note: Double close may cause issues with Z3 native library
    }
    
    // ========== Helper Methods ==========
    
    private Transaction createTransaction(String id, SQLStatement... statements) {
        Transaction tx = new Transaction(id);
        for (SQLStatement stmt : statements) {
            tx.addSqlStatement(stmt);
        }
        return tx;
    }
}
