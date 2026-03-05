package com.rawan.deadlock.solver;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Status;
import com.rawan.deadlock.model.Lock;
import com.rawan.deadlock.model.SQLStatement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ConflictConditionGeneratorTest {
    
    private Context ctx;
    private ConflictConditionGenerator generator;
    
    @Before
    public void setUp() {
        ctx = new Context();
        generator = new ConflictConditionGenerator(ctx);
    }
    
    @After
    public void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNullContext() {
        new ConflictConditionGenerator(null);
    }
    
    @Test
    public void testGenerateConflictConditionSameTableWriteWrite() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        assertFalse(conflict.isFalse()); // Should have potential conflict
    }
    
    @Test
    public void testGenerateConflictConditionSameTableReadWrite() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        assertFalse(conflict.isFalse()); // Should have potential conflict
    }
    
    @Test
    public void testGenerateConflictConditionSameTableReadRead() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        assertTrue(conflict.isFalse()); // No conflict between reads
    }
    
    @Test
    public void testGenerateConflictConditionDifferentTables() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("orders"), "id = 1", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        assertTrue(conflict.isFalse()); // Different tables - no conflict
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testGenerateConflictConditionNullStatements() {
        generator.generateConflictCondition(null, null);
    }
    
    @Test
    public void testGenerateConflictConditionNullTableName() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, null, "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        assertTrue(conflict.isFalse()); // Null table name - no conflict
    }
    
    @Test
    public void testUnifyQueryConditionsSimpleEquality() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, "result");
        
        assertNotNull(expr);
    }
    
    @Test
    public void testUnifyQueryConditionsRangeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age > 18", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testUnifyQueryConditionsEmptyCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
        assertTrue(expr.isTrue()); // Empty condition should return true
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testUnifyQueryConditionsNullStatement() {
        generator.unifyQueryConditions(null, "result");
    }
    
    @Test
    public void testGenerateRangeConflictConditionWithPredicates() {
        Lock rangeLock = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        rangeLock.addPredicate("id > 10");
        rangeLock.addPredicate("id < 20");
        
        BoolExpr rangeCondition = generator.generateRangeConflictCondition(rangeLock);
        
        assertNotNull(rangeCondition);
        assertFalse(rangeCondition.isFalse());
    }
    
    @Test
    public void testGenerateRangeConflictConditionEmptyPredicates() {
        Lock rangeLock = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        
        BoolExpr rangeCondition = generator.generateRangeConflictCondition(rangeLock);
        
        assertNotNull(rangeCondition);
        assertTrue(rangeCondition.isTrue()); // No predicates - assume full range
    }
    
    @Test
    public void testGenerateRangeConflictConditionNotRangeLock() {
        Lock rowLock = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        
        BoolExpr rangeCondition = generator.generateRangeConflictCondition(rowLock);
        
        assertNotNull(rangeCondition);
        assertTrue(rangeCondition.isFalse()); // Not a range lock
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testGenerateRangeConflictConditionNullLock() {
        generator.generateRangeConflictCondition(null);
    }
    
    @Test
    public void testGenerateRangeOverlapCondition() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("id > 10");
        lock1.addPredicate("id < 20");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.SHARED);
        lock2.addPredicate("id > 15");
        lock2.addPredicate("id < 25");
        
        BoolExpr overlap = generator.generateRangeOverlapCondition(lock1, lock2);
        
        assertNotNull(overlap);
        // Ranges [10, 20) and [15, 25) overlap at [15, 20)
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testGenerateRangeOverlapConditionNullLocks() {
        generator.generateRangeOverlapCondition(null, null);
    }
    
    @Test
    public void testParseEqualityCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 5", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseRangeConditionGreaterThan() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age > 18", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseRangeConditionLessThan() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age < 65", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseRangeConditionGreaterThanOrEqual() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age >= 18", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseRangeConditionLessThanOrEqual() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age <= 65", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseBetweenCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age BETWEEN 18 AND 65", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseInCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id IN (1, 2, 3)", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseLikeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "name LIKE 'John%'", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
        assertTrue(expr.isTrue()); // LIKE is conservatively treated as true
    }
    
    @Test
    public void testParseAndCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "age > 18 AND age < 65", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testParseOrCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1 OR id = 2", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
    
    @Test
    public void testSymbolTableManagement() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        generator.unifyQueryConditions(stmt, "result");
        
        assertFalse(generator.getSymbolTable().isEmpty());
        
        generator.clearSymbolTable();
        
        assertTrue(generator.getSymbolTable().isEmpty());
    }
    
    @Test
    public void testConflictConditionWithOverlappingPredicates() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id > 10", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id < 20", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        
        // Check if the conflict is satisfiable (ranges overlap)
        com.microsoft.z3.Solver solver = ctx.mkSolver();
        solver.add(conflict);
        Status status = solver.check();
        
        assertEquals(Status.SATISFIABLE, status); // Ranges [10, inf) and (-inf, 20) overlap
    }
    
    @Test
    public void testConflictConditionWithNonOverlappingPredicates() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id < 10", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id > 20", 2);
        
        BoolExpr conflict = generator.generateConflictCondition(stmt1, stmt2);
        
        assertNotNull(conflict);
        
        // Check if the conflict is unsatisfiable (ranges don't overlap)
        com.microsoft.z3.Solver solver = ctx.mkSolver();
        solver.add(conflict);
        Status status = solver.check();
        
        assertEquals(Status.UNSATISFIABLE, status); // Ranges (-inf, 10) and (20, inf) don't overlap
    }
    
    @Test
    public void testStringConditionParsing() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "name = 'John'", 1);
        
        BoolExpr expr = (BoolExpr) generator.unifyQueryConditions(stmt, null);
        
        assertNotNull(expr);
    }
}
