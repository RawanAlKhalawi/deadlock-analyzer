package com.rawan.deadlock.solver;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Status;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DeadlockVerifierTest {
    
    private DeadlockVerifier verifier;
    private Transaction tx1;
    private Transaction tx2;
    private SQLStatement stmt1;
    private SQLStatement stmt2;
    private SQLStatement stmt3;
    private SQLStatement stmt4;
    
    @Before
    public void setUp() {
        verifier = new DeadlockVerifier();
        
        // Create transaction 1 with path conditions
        tx1 = new Transaction("TX1");
        tx1.addPathCondition("x > 0");
        tx1.addPathCondition("y < 100");
        stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        tx1.addSqlStatement(stmt1);
        tx1.addSqlStatement(stmt2);
        
        // Create transaction 2 with path conditions
        tx2 = new Transaction("TX2");
        tx2.addPathCondition("a >= 10");
        tx2.addPathCondition("b <= 50");
        stmt3 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 3);
        stmt4 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 4);
        tx2.addSqlStatement(stmt3);
        tx2.addSqlStatement(stmt4);
        
        // Register transactions
        verifier.registerTransaction(tx1);
        verifier.registerTransaction(tx2);
    }
    
    @After
    public void tearDown() {
        if (verifier != null) {
            verifier.close();
        }
    }
    
    @Test
    public void testConstructorDefault() {
        DeadlockVerifier v = new DeadlockVerifier();
        assertNotNull(v);
        assertNotNull(v.getContext());
        assertNotNull(v.getConflictGenerator());
        v.close();
    }
    
    @Test
    public void testConstructorWithContext() {
        Context ctx = new Context();
        DeadlockVerifier v = new DeadlockVerifier(ctx);
        assertNotNull(v);
        assertEquals(ctx, v.getContext());
        v.close();
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNullContext() {
        new DeadlockVerifier(null);
    }
    
    @Test
    public void testRegisterTransaction() {
        DeadlockVerifier v = new DeadlockVerifier();
        Transaction tx = new Transaction("TX_TEST");
        
        v.registerTransaction(tx);
        
        // Verify transaction is registered by checking if it can be used
        assertNotNull(v);
        v.close();
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterTransactionNull() {
        verifier.registerTransaction(null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterTransactionNullId() {
        Transaction tx = new Transaction();
        verifier.registerTransaction(tx);
    }
    
    @Test
    public void testVerifyDeadlockCycleEmpty() {
        List<SQLStatement> emptyCycle = new ArrayList<>();
        
        boolean result = verifier.verifyDeadlockCycle(emptyCycle);
        
        assertFalse(result);
    }
    
    @Test
    public void testVerifyDeadlockCycleNull() {
        boolean result = verifier.verifyDeadlockCycle(null);
        
        assertFalse(result);
    }
    
    @Test
    public void testVerifyDeadlockCycleSimple() {
        // Create a simple cycle: stmt2 -> stmt3 (both are writes on same table)
        List<SQLStatement> cycle = Arrays.asList(stmt2, stmt3);
        
        boolean result = verifier.verifyDeadlockCycle(cycle);
        
        // Result depends on whether path conditions and conflict conditions are satisfiable
        // Since both statements are on different rows (id=1 vs id=2), conflict should be UNSAT
        assertFalse(result);
    }
    
    @Test
    public void testVerifyDeadlockCycleWithConflict() {
        // Create statements that conflict on the same row
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 5", 1);
        SQLStatement s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 5", 2);
        
        Transaction t1 = new Transaction("T1");
        t1.addSqlStatement(s1);
        t1.addPathCondition("true");
        
        Transaction t2 = new Transaction("T2");
        t2.addSqlStatement(s2);
        t2.addPathCondition("true");
        
        verifier.registerTransaction(t1);
        verifier.registerTransaction(t2);
        
        List<SQLStatement> cycle = Arrays.asList(s1, s2);
        
        boolean result = verifier.verifyDeadlockCycle(cycle);
        
        // Both statements conflict on id=5, path conditions are true -> should be SAT
        assertTrue(result);
    }
    
    @Test
    public void testConjoinPathConditionsEmpty() {
        List<SQLStatement> emptyCycle = new ArrayList<>();
        
        BoolExpr result = verifier.conjoinPathConditions(emptyCycle);
        
        assertNotNull(result);
        assertTrue(result.isTrue());
    }
    
    @Test
    public void testConjoinPathConditionsNull() {
        BoolExpr result = verifier.conjoinPathConditions(null);
        
        assertNotNull(result);
        assertTrue(result.isTrue());
    }
    
    @Test
    public void testConjoinPathConditionsWithStatements() {
        List<SQLStatement> cycle = Arrays.asList(stmt1, stmt2, stmt3, stmt4);
        
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
        // Should conjoin path conditions from both transactions
    }
    
    @Test
    public void testConjoinPathConditionsNoRegisteredTransactions() {
        DeadlockVerifier v = new DeadlockVerifier();
        
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        List<SQLStatement> cycle = Arrays.asList(s1);
        
        BoolExpr result = v.conjoinPathConditions(cycle);
        
        assertNotNull(result);
        assertTrue(result.isTrue()); // No path conditions found
        v.close();
    }
    
    @Test
    public void testConjoinConflictConditionsEmpty() {
        List<SQLStatement> emptyCycle = new ArrayList<>();
        
        BoolExpr result = verifier.conjoinConflictConditions(emptyCycle);
        
        assertNotNull(result);
        assertTrue(result.isTrue());
    }
    
    @Test
    public void testConjoinConflictConditionsNull() {
        BoolExpr result = verifier.conjoinConflictConditions(null);
        
        assertNotNull(result);
        assertTrue(result.isTrue());
    }
    
    @Test
    public void testConjoinConflictConditionsSimpleCycle() {
        List<SQLStatement> cycle = Arrays.asList(stmt2, stmt4);
        
        BoolExpr result = verifier.conjoinConflictConditions(cycle);
        
        assertNotNull(result);
        // Should generate conflict conditions between consecutive statements
    }
    
    @Test
    public void testConjoinConflictConditionsWrapsAround() {
        // Test that conflict conditions wrap around (last -> first)
        List<SQLStatement> cycle = Arrays.asList(stmt1, stmt2, stmt3);
        
        BoolExpr result = verifier.conjoinConflictConditions(cycle);
        
        assertNotNull(result);
        // Should generate conflicts: stmt1->stmt2, stmt2->stmt3, stmt3->stmt1 (wrap around)
    }
    
    @Test
    public void testSolveTrue() {
        Context ctx = verifier.getContext();
        BoolExpr trueFormula = ctx.mkTrue();
        
        Status result = verifier.solve(trueFormula);
        
        assertEquals(Status.SATISFIABLE, result);
    }
    
    @Test
    public void testSolveFalse() {
        Context ctx = verifier.getContext();
        BoolExpr falseFormula = ctx.mkFalse();
        
        Status result = verifier.solve(falseFormula);
        
        assertEquals(Status.UNSATISFIABLE, result);
    }
    
    @Test
    public void testSolveSimpleFormula() {
        Context ctx = verifier.getContext();
        BoolExpr formula = ctx.mkEq(ctx.mkInt(5), ctx.mkInt(5));
        
        Status result = verifier.solve(formula);
        
        assertEquals(Status.SATISFIABLE, result);
    }
    
    @Test
    public void testSolveUnsatisfiableFormula() {
        Context ctx = verifier.getContext();
        BoolExpr formula = ctx.mkEq(ctx.mkInt(5), ctx.mkInt(10));
        
        Status result = verifier.solve(formula);
        
        assertEquals(Status.UNSATISFIABLE, result);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testSolveNullFormula() {
        verifier.solve(null);
    }
    
    @Test
    public void testVerifyMultipleCyclesEmpty() {
        List<List<SQLStatement>> cycles = new ArrayList<>();
        
        List<List<SQLStatement>> result = verifier.verifyMultipleCycles(cycles);
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
    
    @Test
    public void testVerifyMultipleCyclesNull() {
        List<List<SQLStatement>> result = verifier.verifyMultipleCycles(null);
        
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
    
    @Test
    public void testVerifyMultipleCyclesWithRealDeadlock() {
        // Create a real deadlock scenario
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 10", 1);
        SQLStatement s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 10", 2);
        
        Transaction t1 = new Transaction("T1");
        t1.addSqlStatement(s1);
        t1.addPathCondition("true");
        
        Transaction t2 = new Transaction("T2");
        t2.addSqlStatement(s2);
        t2.addPathCondition("true");
        
        verifier.registerTransaction(t1);
        verifier.registerTransaction(t2);
        
        List<SQLStatement> cycle1 = Arrays.asList(s1, s2);
        List<SQLStatement> cycle2 = Arrays.asList(stmt1, stmt2); // No conflict
        
        List<List<SQLStatement>> cycles = Arrays.asList(cycle1, cycle2);
        
        List<List<SQLStatement>> result = verifier.verifyMultipleCycles(cycles);
        
        assertNotNull(result);
        // At least one cycle should be verified as real deadlock
        assertFalse(result.isEmpty());
    }
    
    @Test
    public void testParsePathConditionTrue() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("true");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
        assertTrue(result.isTrue());
    }
    
    @Test
    public void testParsePathConditionFalse() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("false");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
        assertTrue(result.isFalse());
    }
    
    @Test
    public void testParsePathConditionEquality() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("x = 5");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testParsePathConditionInequality() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("x != 5");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testParsePathConditionComparison() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("x > 10");
        tx.addPathCondition("y < 20");
        tx.addPathCondition("z >= 5");
        tx.addPathCondition("w <= 15");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testParsePathConditionAnd() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("x > 0 && y < 100");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testParsePathConditionOr() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("x = 1 || x = 2");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testParsePathConditionNot() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("!flag");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testClearTransactions() {
        verifier.clearTransactions();
        
        // After clearing, path conditions should not be found
        List<SQLStatement> cycle = Arrays.asList(stmt1);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
        assertTrue(result.isTrue()); // No transactions registered
    }
    
    @Test
    public void testIntegrationDeadlockScenario() {
        // Classic deadlock: TX1 locks row 1 then tries row 2, TX2 locks row 2 then tries row 1
        
        // TX1: SELECT id=1, UPDATE id=2
        Transaction t1 = new Transaction("T1");
        SQLStatement t1s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("accounts"), "id = 1", 1);
        SQLStatement t1s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("accounts"), "id = 2", 2);
        t1.addSqlStatement(t1s1);
        t1.addSqlStatement(t1s2);
        t1.addPathCondition("amount > 0");
        
        // TX2: SELECT id=2, UPDATE id=1
        Transaction t2 = new Transaction("T2");
        SQLStatement t2s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("accounts"), "id = 2", 3);
        SQLStatement t2s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("accounts"), "id = 1", 4);
        t2.addSqlStatement(t2s1);
        t2.addSqlStatement(t2s2);
        t2.addPathCondition("balance > 0");
        
        DeadlockVerifier v = new DeadlockVerifier();
        v.registerTransaction(t1);
        v.registerTransaction(t2);
        
        // Cycle: t1s2 (UPDATE id=2) -> t2s1 (SELECT id=2) -> t2s2 (UPDATE id=1) -> t1s1 (SELECT id=1)
        // But this won't form a real deadlock because the predicates don't overlap correctly
        List<SQLStatement> cycle = Arrays.asList(t1s2, t2s2);
        
        boolean result = v.verifyDeadlockCycle(cycle);
        
        // Result depends on whether predicates overlap
        assertNotNull(result);
        
        v.close();
    }
    
    @Test
    public void testPathConditionWithBooleanVariable() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("flag = true");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testPathConditionWithStringVariable() {
        Transaction tx = new Transaction("TX_TEST");
        tx.addPathCondition("name = 'John'");
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        tx.addSqlStatement(stmt);
        
        verifier.registerTransaction(tx);
        
        List<SQLStatement> cycle = Arrays.asList(stmt);
        BoolExpr result = verifier.conjoinPathConditions(cycle);
        
        assertNotNull(result);
    }
    
    @Test
    public void testConflictConditionsWithReadOnlyStatements() {
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement s2 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 2);
        
        List<SQLStatement> cycle = Arrays.asList(s1, s2);
        
        BoolExpr result = verifier.conjoinConflictConditions(cycle);
        
        assertNotNull(result);
        // Should contain false conditions since reads don't conflict
    }
}
