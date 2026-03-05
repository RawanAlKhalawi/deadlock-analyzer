package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.analyzer.IndexAnalyzer.Index;
import com.rawan.deadlock.model.SQLStatement;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class IndexAnalyzerTest {
    
    private IndexAnalyzer analyzer;
    private Index primaryIndex;
    private Index uniqueIndex;
    private Index secondaryIndex;
    
    @Before
    public void setUp() {
        analyzer = new IndexAnalyzer();
        
        // Register indexes for "users" table
        primaryIndex = new Index("PRIMARY", "users", Arrays.asList("id"), true, true);
        uniqueIndex = new Index("idx_email", "users", Arrays.asList("email"), false, true);
        secondaryIndex = new Index("idx_name", "users", Arrays.asList("name"), false, false);
        
        analyzer.registerIndex(primaryIndex);
        analyzer.registerIndex(uniqueIndex);
        analyzer.registerIndex(secondaryIndex);
    }
    
    @Test
    public void testRegisterIndex() {
        List<Index> indexes = analyzer.getTableIndexes("users");
        assertEquals(3, indexes.size());
        assertTrue(indexes.contains(primaryIndex));
        assertTrue(indexes.contains(uniqueIndex));
        assertTrue(indexes.contains(secondaryIndex));
    }
    
    @Test
    public void testInferIndexesWithPrimaryKey() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertEquals(primaryIndex, indexes.get(0));
    }
    
    @Test
    public void testInferIndexesWithUniqueIndex() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "email = 'test@example.com'", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertEquals(uniqueIndex, indexes.get(0));
    }
    
    @Test
    public void testInferIndexesWithSecondaryIndex() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "name = 'John'", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertTrue(indexes.contains(secondaryIndex));
    }
    
    @Test
    public void testInferIndexesNoConditions() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        // Should return primary key or first index
        assertEquals(primaryIndex, indexes.get(0));
    }
    
    @Test
    public void testInferIndexesNullStatement() {
        List<Index> indexes = analyzer.inferIndexes(null);
        assertTrue(indexes.isEmpty());
    }
    
    @Test
    public void testInferIndexesNoRegisteredIndexes() {
        IndexAnalyzer newAnalyzer = new IndexAnalyzer();
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("products"), "id = 1", 1);
        List<Index> indexes = newAnalyzer.inferIndexes(stmt);
        
        // Should return default primary key
        assertFalse(indexes.isEmpty());
        assertEquals("PRIMARY", indexes.get(0).getIndexName());
        assertEquals("products", indexes.get(0).getTableName());
    }
    
    @Test
    public void testBuildIndexUsageGraphSingleStatement() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        analyzer.buildIndexUsageGraph(Arrays.asList(stmt));
        
        Map<Index, List<Index>> graph = analyzer.getIndexGraph();
        assertFalse(graph.isEmpty());
    }
    
    @Test
    public void testBuildIndexUsageGraphMultipleStatements() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        
        analyzer.buildIndexUsageGraph(Arrays.asList(stmt1, stmt2));
        
        Map<Index, List<Index>> graph = analyzer.getIndexGraph();
        assertFalse(graph.isEmpty());
        
        // Check that edges were created
        boolean hasEdges = false;
        for (List<Index> neighbors : graph.values()) {
            if (!neighbors.isEmpty()) {
                hasEdges = true;
                break;
            }
        }
        assertTrue(hasEdges);
    }
    
    @Test
    public void testBuildIndexUsageGraphEmptyList() {
        analyzer.buildIndexUsageGraph(Arrays.asList());
        Map<Index, List<Index>> graph = analyzer.getIndexGraph();
        assertTrue(graph.isEmpty());
    }
    
    @Test
    public void testBuildIndexUsageGraphNullList() {
        analyzer.buildIndexUsageGraph(null);
        Map<Index, List<Index>> graph = analyzer.getIndexGraph();
        assertTrue(graph.isEmpty());
    }
    
    @Test
    public void testFindIndexAccessSequencesEmpty() {
        List<List<Index>> sequences = analyzer.findIndexAccessSequences();
        assertTrue(sequences.isEmpty());
    }
    
    @Test
    public void testFindIndexAccessSequencesSimple() {
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 2);
        
        analyzer.buildIndexUsageGraph(Arrays.asList(stmt1, stmt2));
        List<List<Index>> sequences = analyzer.findIndexAccessSequences();
        
        assertFalse(sequences.isEmpty());
    }
    
    @Test
    public void testFindIndexAccessSequencesWithCycle() {
        // Create a scenario that might produce a cycle
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "email = 'test@example.com'", 2);
        SQLStatement stmt3 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 3);
        
        analyzer.buildIndexUsageGraph(Arrays.asList(stmt1, stmt2, stmt3));
        List<List<Index>> sequences = analyzer.findIndexAccessSequences();
        
        // Should handle cycles gracefully
        assertNotNull(sequences);
    }
    
    @Test
    public void testIndexEquality() {
        Index idx1 = new Index("PRIMARY", "users", Arrays.asList("id"), true, true);
        Index idx2 = new Index("PRIMARY", "users", Arrays.asList("id"), true, true);
        Index idx3 = new Index("idx_email", "users", Arrays.asList("email"), false, true);
        
        assertEquals(idx1, idx2);
        assertNotEquals(idx1, idx3);
        assertEquals(idx1.hashCode(), idx2.hashCode());
    }
    
    @Test
    public void testIndexToString() {
        String str = primaryIndex.toString();
        assertTrue(str.contains("PRIMARY"));
        assertTrue(str.contains("users"));
        assertTrue(str.contains("id"));
    }
    
    @Test
    public void testInferIndexesWithRangeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id > 10 AND id < 20", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertEquals(primaryIndex, indexes.get(0));
    }
    
    @Test
    public void testInferIndexesWithLikeCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "name LIKE 'John%'", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertTrue(indexes.contains(secondaryIndex));
    }
    
    @Test
    public void testInferIndexesWithInCondition() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id IN (1, 2, 3)", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        assertEquals(primaryIndex, indexes.get(0));
    }
    
    @Test
    public void testInferIndexesWithMultipleConditions() {
        SQLStatement stmt = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1 AND name = 'John'", 1);
        List<Index> indexes = analyzer.inferIndexes(stmt);
        
        assertFalse(indexes.isEmpty());
        // Should prefer primary key
        assertEquals(primaryIndex, indexes.get(0));
    }
    
    @Test
    public void testGetTableIndexesNonExistent() {
        List<Index> indexes = analyzer.getTableIndexes("nonexistent");
        assertTrue(indexes.isEmpty());
    }
    
    @Test
    public void testBuildIndexUsageGraphDifferentTables() {
        // Register indexes for another table
        Index productsIndex = new Index("PRIMARY", "products", Arrays.asList("id"), true, true);
        analyzer.registerIndex(productsIndex);
        
        SQLStatement stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        SQLStatement stmt2 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("products"), "id = 1", 2);
        
        analyzer.buildIndexUsageGraph(Arrays.asList(stmt1, stmt2));
        
        Map<Index, List<Index>> graph = analyzer.getIndexGraph();
        assertFalse(graph.isEmpty());
        
        // Should have edges between different table indexes
        assertTrue(graph.containsKey(primaryIndex) || graph.containsKey(productsIndex));
    }
}
