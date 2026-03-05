package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.analyzer.IndexAnalyzer.Index;
import com.rawan.deadlock.model.Lock;
import com.rawan.deadlock.model.SQLStatement;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class LockGeneratorTest {
    
    private LockGenerator lockGenerator;
    private IndexAnalyzer indexAnalyzer;
    
    @Before
    public void setUp() {
        indexAnalyzer = new IndexAnalyzer();
        
        // Register indexes for "users" table
        Index primaryIndex = new Index("PRIMARY", "users", Arrays.asList("id"), true, true);
        Index uniqueIndex = new Index("idx_email", "users", Arrays.asList("email"), false, true);
        Index secondaryIndex = new Index("idx_name", "users", Arrays.asList("name"), false, false);
        
        indexAnalyzer.registerIndex(primaryIndex);
        indexAnalyzer.registerIndex(uniqueIndex);
        indexAnalyzer.registerIndex(secondaryIndex);
        
        lockGenerator = new LockGenerator(indexAnalyzer);
    }
    
    // ========== SELECT (Shared Locks) Tests ==========
    
    @Test
    public void testGenerateSharedLocksSelectWithEmptyResult() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 999", 1);
        List<Lock> locks = lockGenerator.generateSharedLocks(stmt, true);
        
        assertFalse(locks.isEmpty());
        // Should generate gap lock for empty result
        boolean hasGapLock = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("GAP_LOCK")));
        assertTrue(hasGapLock);
        
        // All locks should be SHARED
        for (Lock lock : locks) {
            assertEquals(Lock.LockMode.SHARED, lock.getLockMode());
        }
    }
    
    @Test
    public void testGenerateSharedLocksSelectWithNonEmptyResult() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = lockGenerator.generateSharedLocks(stmt, false);
        
        assertFalse(locks.isEmpty());
        // Should generate row lock for exact match on unique index
        boolean hasRowLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.ROW);
        assertTrue(hasRowLock);
        
        // All locks should be SHARED
        for (Lock lock : locks) {
            assertEquals(Lock.LockMode.SHARED, lock.getLockMode());
        }
    }
    
    @Test
    public void testGenerateSharedLocksSelectWithRangeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id > 10 AND id < 20", 1);
        List<Lock> locks = lockGenerator.generateSharedLocks(stmt, false);
        
        assertFalse(locks.isEmpty());
        // Should generate range locks for range condition
        boolean hasRangeLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.RANGE);
        assertTrue(hasRangeLock);
        
        // Should also have gap lock
        boolean hasGapLock = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("GAP_LOCK")));
        assertTrue(hasGapLock);
    }
    
    @Test
    public void testGenerateSharedLocksNullStatement() {
        List<Lock> locks = lockGenerator.generateSharedLocks(null, false);
        assertTrue(locks.isEmpty());
    }
    
    @Test
    public void testGenerateSharedLocksNonSelectStatement() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = lockGenerator.generateSharedLocks(stmt, false);
        assertTrue(locks.isEmpty());
    }
    
    // ========== INSERT (Exclusive Locks) Tests ==========
    
    @Test
    public void testGenerateExclusiveLocksInsert() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.INSERT, Arrays.asList("users"), "", 1);
        stmt.addParameter("id", 100);
        stmt.addParameter("name", "John");
        
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have insert intention lock
        boolean hasInsertIntention = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("INSERT_INTENTION")));
        assertTrue(hasInsertIntention);
        
        // Should have row lock
        boolean hasRowLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.ROW);
        assertTrue(hasRowLock);
        
        // All locks should be EXCLUSIVE
        for (Lock lock : locks) {
            assertEquals(Lock.LockMode.EXCLUSIVE, lock.getLockMode());
        }
    }
    
    // ========== UPDATE (Exclusive Locks) Tests ==========
    
    @Test
    public void testGenerateExclusiveLocksUpdateExactMatch() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have row lock for exact match
        boolean hasRowLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.ROW);
        assertTrue(hasRowLock);
        
        // All locks should be EXCLUSIVE
        for (Lock lock : locks) {
            assertEquals(Lock.LockMode.EXCLUSIVE, lock.getLockMode());
        }
    }
    
    @Test
    public void testGenerateExclusiveLocksUpdateRangeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id > 10 AND id < 20", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have range locks
        boolean hasRangeLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.RANGE);
        assertTrue(hasRangeLock);
        
        // Should have gap lock
        boolean hasGapLock = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("GAP_LOCK")));
        assertTrue(hasGapLock);
    }
    
    @Test
    public void testGenerateExclusiveLocksUpdateNonUniqueIndex() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "name = 'John'", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have range lock for non-unique index
        boolean hasRangeLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.RANGE);
        assertTrue(hasRangeLock);
    }
    
    // ========== DELETE (Exclusive Locks) Tests ==========
    
    @Test
    public void testGenerateExclusiveLocksDeleteExactMatch() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.DELETE, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have row lock
        boolean hasRowLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.ROW);
        assertTrue(hasRowLock);
        
        // Should have gap lock to prevent phantom reads
        boolean hasGapLock = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("GAP_LOCK")));
        assertTrue(hasGapLock);
    }
    
    @Test
    public void testGenerateExclusiveLocksDeleteRangeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.DELETE, Arrays.asList("users"), "id > 10", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should have range locks
        boolean hasRangeLock = locks.stream()
            .anyMatch(lock -> lock.getLockGranularity() == Lock.LockGranularity.RANGE);
        assertTrue(hasRangeLock);
        
        // Should have gap lock
        boolean hasGapLock = locks.stream()
            .anyMatch(lock -> lock.getPredicates().stream()
                .anyMatch(p -> p.contains("GAP_LOCK")));
        assertTrue(hasGapLock);
    }
    
    @Test
    public void testGenerateExclusiveLocksNullStatement() {
        List<Lock> locks = lockGenerator.generateExclusiveLocks(null);
        assertTrue(locks.isEmpty());
    }
    
    @Test
    public void testGenerateExclusiveLocksSelectStatement() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        assertTrue(locks.isEmpty());
    }
    
    // ========== Lock Conflict Detection Tests ==========
    
    @Test
    public void testDetectLockConflictSharedShared() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
        lock1.addPredicate("id = 1");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
        lock2.addPredicate("id = 1");
        
        // Shared locks are compatible
        assertFalse(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictSharedExclusive() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
        lock1.addPredicate("id = 1");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("id = 1");
        
        // Shared and exclusive locks conflict
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictExclusiveExclusive() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("id = 1");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("id = 1");
        
        // Exclusive locks conflict
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictDifferentIndexes() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("id = 1");
        
        Lock lock2 = new Lock("idx_email", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("email = 'test@example.com'");
        
        // Locks on different indexes don't conflict
        assertFalse(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictDifferentRows() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("id = 1");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("id = 2");
        
        // Locks on different rows don't conflict
        assertFalse(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictTableLock() {
        Lock lock1 = new Lock("users_table", Lock.LockGranularity.TABLE, Lock.LockMode.EXCLUSIVE);
        
        Lock lock2 = new Lock("users_PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
        lock2.addPredicate("id = 1");
        
        // Table lock conflicts with any lock on the same table
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictRangeLocks() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("id > 10");
        lock1.addPredicate("id < 20");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("id > 15");
        lock2.addPredicate("id < 25");
        
        // Range locks conflict if they overlap (conservative approach)
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictGapLocks() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("GAP_LOCK");
        lock1.addPredicate("id = 5");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("GAP_LOCK");
        lock2.addPredicate("id = 5");
        
        // Gap locks can conflict
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictInsertIntentionWithGapLock() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock1.addPredicate("INSERT_INTENTION");
        lock1.addPredicate("id = 5");
        
        Lock lock2 = new Lock("PRIMARY", Lock.LockGranularity.RANGE, Lock.LockMode.EXCLUSIVE);
        lock2.addPredicate("GAP_LOCK");
        lock2.addPredicate("id > 1");
        lock2.addPredicate("id < 10");
        
        // Insert intention conflicts with gap lock
        assertTrue(lockGenerator.detectLockConflict(lock1, lock2));
    }
    
    @Test
    public void testDetectLockConflictNullLocks() {
        Lock lock1 = new Lock("PRIMARY", Lock.LockGranularity.ROW, Lock.LockMode.SHARED);
        
        assertFalse(lockGenerator.detectLockConflict(null, lock1));
        assertFalse(lockGenerator.detectLockConflict(lock1, null));
        assertFalse(lockGenerator.detectLockConflict(null, null));
    }
    
    // ========== Integration Tests ==========
    
    @Test
    public void testDeadlockScenario() {
        // Transaction 1: SELECT then UPDATE
        SQLStatement t1s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement t1s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2);
        
        // Transaction 2: SELECT then UPDATE (reverse order)
        SQLStatement t2s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 1);
        SQLStatement t2s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        
        // Generate locks
        List<Lock> t1s1Locks = lockGenerator.generateSharedLocks(t1s1, false);
        List<Lock> t1s2Locks = lockGenerator.generateExclusiveLocks(t1s2);
        List<Lock> t2s1Locks = lockGenerator.generateSharedLocks(t2s1, false);
        List<Lock> t2s2Locks = lockGenerator.generateExclusiveLocks(t2s2);
        
        // Verify locks were generated
        assertFalse(t1s1Locks.isEmpty());
        assertFalse(t1s2Locks.isEmpty());
        assertFalse(t2s1Locks.isEmpty());
        assertFalse(t2s2Locks.isEmpty());
        
        // Check for conflicts
        // T1's UPDATE on id=2 should conflict with T2's SELECT on id=2
        boolean hasConflict1 = false;
        for (Lock lock1 : t1s2Locks) {
            for (Lock lock2 : t2s1Locks) {
                if (lockGenerator.detectLockConflict(lock1, lock2)) {
                    hasConflict1 = true;
                    break;
                }
            }
        }
        
        // T2's UPDATE on id=1 should conflict with T1's SELECT on id=1
        boolean hasConflict2 = false;
        for (Lock lock1 : t2s2Locks) {
            for (Lock lock2 : t1s1Locks) {
                if (lockGenerator.detectLockConflict(lock1, lock2)) {
                    hasConflict2 = true;
                    break;
                }
            }
        }
        
        // At least one conflict should be detected
        assertTrue(hasConflict1 || hasConflict2);
    }
    
    @Test
    public void testGetSetIndexAnalyzer() {
        IndexAnalyzer newAnalyzer = new IndexAnalyzer();
        lockGenerator.setIndexAnalyzer(newAnalyzer);
        assertEquals(newAnalyzer, lockGenerator.getIndexAnalyzer());
    }
    
    @Test
    public void testLockGeneratorWithDefaultIndexAnalyzer() {
        LockGenerator generator = new LockGenerator();
        assertNotNull(generator.getIndexAnalyzer());
        
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        List<Lock> locks = generator.generateSharedLocks(stmt, false);
        
        // Should still generate locks with default analyzer
        assertFalse(locks.isEmpty());
    }
    
    @Test
    public void testGenerateLocksWithComplexConditions() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id > 10 AND name = 'John' OR email LIKE '%@example.com'", 1);
        List<Lock> locks = lockGenerator.generateExclusiveLocks(stmt);
        
        assertFalse(locks.isEmpty());
        
        // Should handle complex conditions
        boolean hasLocks = locks.stream()
            .anyMatch(lock -> !lock.getPredicates().isEmpty());
        assertTrue(hasLocks);
    }
}
