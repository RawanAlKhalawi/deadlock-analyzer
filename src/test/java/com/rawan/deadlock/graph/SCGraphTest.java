package com.rawan.deadlock.graph;

import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class SCGraphTest {
    
    private SCGraph graph;
    private Transaction tx1;
    private Transaction tx2;
    private SQLStatement stmt1;
    private SQLStatement stmt2;
    private SQLStatement stmt3;
    private SQLStatement stmt4;
    
    @Before
    public void setUp() {
        graph = new SCGraph();
        
        // Create transaction 1 with 2 statements
        tx1 = new Transaction("TX1");
        stmt1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 1", 1);
        stmt2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 1", 2);
        tx1.addSqlStatement(stmt1);
        tx1.addSqlStatement(stmt2);
        
        // Create transaction 2 with 2 statements
        tx2 = new Transaction("TX2");
        stmt3 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("users"), "id = 2", 3);
        stmt4 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("users"), "id = 2", 4);
        tx2.addSqlStatement(stmt3);
        tx2.addSqlStatement(stmt4);
    }
    
    @Test
    public void testEmptyGraph() {
        assertEquals(0, graph.getVertexCount());
        assertEquals(0, graph.getEdgeCount());
        assertTrue(graph.getVertices().isEmpty());
    }
    
    @Test
    public void testAddTransaction() {
        graph.addTransaction(tx1);
        
        assertEquals(2, graph.getVertexCount());
        assertEquals(1, graph.getEdgeCount()); // One S-edge between stmt1 and stmt2
        
        Set<SQLStatement> vertices = graph.getVertices();
        assertTrue(vertices.contains(stmt1));
        assertTrue(vertices.contains(stmt2));
    }
    
    @Test
    public void testAddMultipleTransactions() {
        graph.addTransaction(tx1);
        graph.addTransaction(tx2);
        
        assertEquals(4, graph.getVertexCount());
        assertEquals(2, graph.getEdgeCount()); // Two S-edges
        
        List<SQLStatement> tx1Statements = graph.getTransactionStatements("TX1");
        assertEquals(2, tx1Statements.size());
        assertTrue(tx1Statements.contains(stmt1));
        assertTrue(tx1Statements.contains(stmt2));
        
        List<SQLStatement> tx2Statements = graph.getTransactionStatements("TX2");
        assertEquals(2, tx2Statements.size());
        assertTrue(tx2Statements.contains(stmt3));
        assertTrue(tx2Statements.contains(stmt4));
    }
    
    @Test
    public void testAddSEdge() {
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.SELECT, Arrays.asList("table1"), "id = 1", 1);
        SQLStatement s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("table1"), "id = 1", 2);
        
        graph.addSEdge(s1, s2);
        
        assertEquals(2, graph.getVertexCount());
        assertEquals(1, graph.getEdgeCount());
        
        List<SCGraph.Edge> edges = graph.getOutgoingEdges(s1);
        assertEquals(1, edges.size());
        assertEquals(SCGraph.EdgeType.S_EDGE, edges.get(0).getType());
        assertEquals(s1, edges.get(0).getSource());
        assertEquals(s2, edges.get(0).getTarget());
    }
    
    @Test
    public void testAddCEdge() {
        SQLStatement s1 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("table1"), "id = 1", 1);
        SQLStatement s2 = new SQLStatement(SQLStatement.StatementType.UPDATE, Arrays.asList("table1"), "id = 1", 2);
        
        graph.addCEdge(s1, s2);
        
        assertEquals(2, graph.getVertexCount());
        assertEquals(1, graph.getEdgeCount());
        
        List<SCGraph.Edge> edges = graph.getOutgoingEdges(s1);
        assertEquals(1, edges.size());
        assertEquals(SCGraph.EdgeType.C_EDGE, edges.get(0).getType());
        assertEquals(s1, edges.get(0).getSource());
        assertEquals(s2, edges.get(0).getTarget());
    }
    
    @Test
    public void testFindCyclesNoCycle() {
        graph.addTransaction(tx1);
        graph.addTransaction(tx2);
        
        List<List<SQLStatement>> cycles = graph.findCycles();
        assertTrue(cycles.isEmpty());
    }
    
    @Test
    public void testFindCyclesWithSimpleCycle() {
        // Create a valid S→C alternating cycle across two transactions:
        // stmt1 (TX1) -S-> stmt2 (TX1) -C-> stmt3 (TX2) -S-> stmt4 (TX2) -C-> stmt1 (TX1)
        graph.addTransaction(tx1);
        graph.addTransaction(tx2);
        graph.addCEdge(stmt2, stmt3);
        graph.addCEdge(stmt4, stmt1);
        
        List<List<SQLStatement>> cycles = graph.findCycles();
        assertFalse(cycles.isEmpty());
        assertEquals(1, cycles.size());
        
        List<SQLStatement> cycle = cycles.get(0);
        assertEquals(4, cycle.size());
        assertTrue(cycle.contains(stmt1));
        assertTrue(cycle.contains(stmt2));
        assertTrue(cycle.contains(stmt3));
        assertTrue(cycle.contains(stmt4));
    }
    
    @Test
    public void testFindCyclesWithComplexCycle() {
        // Create a cycle: stmt1 -> stmt2 -> stmt3 -> stmt4 -> stmt1
        graph.addSEdge(stmt1, stmt2);
        graph.addCEdge(stmt2, stmt3);
        graph.addSEdge(stmt3, stmt4);
        graph.addCEdge(stmt4, stmt1);
        
        List<List<SQLStatement>> cycles = graph.findCycles();
        assertFalse(cycles.isEmpty());
        
        // Should find at least one cycle
        assertTrue(cycles.size() >= 1);
    }
    
    @Test
    public void testDeadlockScenario() {
        // Classic deadlock scenario:
        // TX1: stmt1 (SELECT) -> stmt2 (UPDATE)  [S-edge from addTransaction]
        // TX2: stmt3 (SELECT) -> stmt4 (UPDATE)  [S-edge from addTransaction]
        // To create a cycle representing deadlock:
        // stmt2 conflicts with stmt3 (TX1's UPDATE conflicts with TX2's SELECT)
        // stmt4 conflicts with stmt1 (TX2's UPDATE conflicts with TX1's SELECT)
        // This creates cycle: stmt1 -> stmt2 -> stmt3 -> stmt4 -> stmt1
        
        graph.addTransaction(tx1);
        graph.addTransaction(tx2);
        
        // Add conflict edges to create a cycle
        graph.addCEdge(stmt2, stmt3); // TX1's UPDATE conflicts with TX2's SELECT
        graph.addCEdge(stmt4, stmt1); // TX2's UPDATE conflicts with TX1's SELECT
        
        List<List<SQLStatement>> cycles = graph.findCycles();
        assertFalse("Deadlock cycle should be detected", cycles.isEmpty());
    }
    
    @Test
    public void testGetOutgoingEdges() {
        graph.addTransaction(tx1);
        
        List<SCGraph.Edge> edges = graph.getOutgoingEdges(stmt1);
        assertEquals(1, edges.size());
        assertEquals(stmt2, edges.get(0).getTarget());
        
        List<SCGraph.Edge> noEdges = graph.getOutgoingEdges(stmt2);
        assertTrue(noEdges.isEmpty());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testAddTransactionNull() {
        graph.addTransaction(null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testAddSEdgeNullSource() {
        graph.addSEdge(null, stmt1);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testAddCEdgeNullTarget() {
        graph.addCEdge(stmt1, null);
    }
    
    @Test
    public void testAddTransactionWithEmptyStatements() {
        Transaction emptyTx = new Transaction("TX_EMPTY");
        graph.addTransaction(emptyTx);
        
        assertEquals(0, graph.getVertexCount());
        assertEquals(0, graph.getEdgeCount());
    }
    
    @Test
    public void testGetTransactionStatementsNonExistent() {
        List<SQLStatement> statements = graph.getTransactionStatements("NON_EXISTENT");
        assertNotNull(statements);
        assertTrue(statements.isEmpty());
    }
    
    @Test
    public void testToString() {
        graph.addTransaction(tx1);
        String result = graph.toString();
        
        assertNotNull(result);
        assertTrue(result.contains("SCGraph"));
        assertTrue(result.contains("vertices=2"));
        assertTrue(result.contains("edges=1"));
        assertTrue(result.contains("transactions=1"));
    }
    
    @Test
    public void testEdgeToString() {
        SCGraph.Edge edge = new SCGraph.Edge(stmt1, stmt2, SCGraph.EdgeType.S_EDGE);
        String result = edge.toString();
        
        assertNotNull(result);
        assertTrue(result.contains("S_EDGE"));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("2"));
    }
}
