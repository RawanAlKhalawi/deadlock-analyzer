package com.rawan.deadlock.graph;

import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;

import java.util.*;

/**
 * SC-Graph (Statement-Conflict Graph) for deadlock analysis.
 * Vertices represent SQL statements.
 * S-edges connect consecutive statements from the same transaction.
 * C-edges connect conflicting statements from different transactions.
 */
public class SCGraph {
    
    /**
     * Edge types in the SC-Graph
     */
    public enum EdgeType {
        S_EDGE,  // Sequential edge (same transaction)
        C_EDGE   // Conflict edge (different transactions)
    }
    
    /**
     * Represents an edge in the graph
     */
    public static class Edge {
        private final SQLStatement source;
        private final SQLStatement target;
        private final EdgeType type;
        
        public Edge(SQLStatement source, SQLStatement target, EdgeType type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }
        
        public SQLStatement getSource() {
            return source;
        }
        
        public SQLStatement getTarget() {
            return target;
        }
        
        public EdgeType getType() {
            return type;
        }
        
        @Override
        public String toString() {
            return "Edge{" + type + ": " + source.getExecutionOrder() + " -> " + target.getExecutionOrder() + "}";
        }
    }
    
    // Adjacency list: maps each statement to its outgoing edges
    private final Map<SQLStatement, List<Edge>> adjacencyList;
    
    // Set of all vertices (SQL statements)
    private final Set<SQLStatement> vertices;
    
    // Map transaction ID to its statements for easy lookup
    private final Map<String, List<SQLStatement>> transactionStatements;
    
    public SCGraph() {
        this.adjacencyList = new HashMap<>();
        this.vertices = new HashSet<>();
        this.transactionStatements = new HashMap<>();
    }
    
    /**
     * Adds a transaction to the graph.
     * Creates vertices for all SQL statements and S-edges between consecutive statements.
     * 
     * @param tx the transaction to add
     */
    public void addTransaction(Transaction tx) {
        if (tx == null || tx.getTransactionId() == null) {
            throw new IllegalArgumentException("Transaction and transaction ID cannot be null");
        }
        
        List<SQLStatement> statements = tx.getSqlStatements();
        if (statements == null || statements.isEmpty()) {
            return;
        }
        
        // Store transaction statements mapping
        transactionStatements.put(tx.getTransactionId(), new ArrayList<>(statements));
        
        // Add all statements as vertices
        for (SQLStatement stmt : statements) {
            addVertex(stmt);
        }
        
        // Add S-edges between consecutive statements
        for (int i = 0; i < statements.size() - 1; i++) {
            addSEdge(statements.get(i), statements.get(i + 1));
        }
    }
    
    /**
     * Adds a vertex (SQL statement) to the graph.
     * 
     * @param stmt the SQL statement to add
     */
    private void addVertex(SQLStatement stmt) {
        if (stmt != null) {
            vertices.add(stmt);
            adjacencyList.putIfAbsent(stmt, new ArrayList<>());
        }
    }
    
    /**
     * Adds an S-edge (sequential edge) between two statements from the same transaction.
     * 
     * @param stmt1 the source statement
     * @param stmt2 the target statement
     */
    public void addSEdge(SQLStatement stmt1, SQLStatement stmt2) {
        if (stmt1 == null || stmt2 == null) {
            throw new IllegalArgumentException("Statements cannot be null");
        }
        
        addVertex(stmt1);
        addVertex(stmt2);
        
        Edge edge = new Edge(stmt1, stmt2, EdgeType.S_EDGE);
        adjacencyList.get(stmt1).add(edge);
    }
    
    /**
     * Adds a C-edge (conflict edge) between two statements from different transactions.
     * 
     * @param stmt1 the source statement
     * @param stmt2 the target statement
     */
    public void addCEdge(SQLStatement stmt1, SQLStatement stmt2) {
        if (stmt1 == null || stmt2 == null) {
            throw new IllegalArgumentException("Statements cannot be null");
        }
        
        addVertex(stmt1);
        addVertex(stmt2);
        
        Edge edge = new Edge(stmt1, stmt2, EdgeType.C_EDGE);
        adjacencyList.get(stmt1).add(edge);
    }
    
    /**
     * Finds all cycles in the graph using depth-first search.
     * 
     * @return a list of cycles, where each cycle is a list of SQL statements
     */
    public List<List<SQLStatement>> findCycles() {
        List<List<SQLStatement>> cycles = new ArrayList<>();
        Set<SQLStatement> visited = new HashSet<>();
        Set<SQLStatement> recursionStack = new HashSet<>();
        Deque<SQLStatement> path = new ArrayDeque<>();
        
        for (SQLStatement vertex : vertices) {
            if (!visited.contains(vertex)) {
                findCyclesUtil(vertex, visited, recursionStack, path, cycles);
            }
        }
        
        return cycles;
    }
    
    /**
     * Utility method for cycle detection using DFS.
     * Uses a path stack to track the current DFS path for correct cycle reconstruction.
     */
    private void findCyclesUtil(SQLStatement current, Set<SQLStatement> visited, 
                                Set<SQLStatement> recursionStack, Deque<SQLStatement> path,
                                List<List<SQLStatement>> cycles) {
        visited.add(current);
        recursionStack.add(current);
        path.push(current);
        
        List<Edge> edges = adjacencyList.get(current);
        if (edges != null) {
            for (Edge edge : edges) {
                SQLStatement neighbor = edge.getTarget();
                
                if (!visited.contains(neighbor)) {
                    findCyclesUtil(neighbor, visited, recursionStack, path, cycles);
                } else if (recursionStack.contains(neighbor)) {
                    // Back-edge found - extract cycle as subpath from neighbor to current
                    List<SQLStatement> cycle = new ArrayList<>();
                    for (SQLStatement node : path) {
                        cycle.add(node);
                        if (node == neighbor) {
                            break;
                        }
                    }
                    // Reverse since Deque iteration is top-first (current→...→neighbor)
                    Collections.reverse(cycle);
                    
                    // Validate that the cycle alternates S→C→S→C
                    if (cycle.size() >= 2 && isValidDeadlockCycle(cycle)) {
                        cycles.add(cycle);
                    }
                }
            }
        }
        
        path.pop();
        recursionStack.remove(current);
    }
    
    /**
     * Validates that a candidate cycle strictly alternates between S-edges and C-edges.
     * WeSEER requires deadlock cycles to follow the pattern: S → C → S → C → ...
     * 
     * @param cycle the candidate cycle to validate
     * @return true if edges alternate S → C → S → C, false otherwise
     */
    private boolean isValidDeadlockCycle(List<SQLStatement> cycle) {
        if (cycle.size() < 2) {
            return false;
        }
        
        // 1. Odd-length cycles are invalid — must have equal S-edges and C-edges
        if (cycle.size() % 2 != 0) {
            return false;
        }
        
        // 2. Determine edge types by comparing transactionIds (strip _instance2 suffix)
        //    Fall back to graph edge type if transactionIds are not available
        boolean[] isSEdge = new boolean[cycle.size()];
        for (int i = 0; i < cycle.size(); i++) {
            SQLStatement current = cycle.get(i);
            SQLStatement next = cycle.get((i + 1) % cycle.size());
            
            String txCurrent = current.getTransactionId();
            String txNext = next.getTransactionId();
            
            if (txCurrent != null && txNext != null) {
                String cleanCurrent = txCurrent.replaceAll("_instance2$", "");
                String cleanNext = txNext.replaceAll("_instance2$", "");
                // same transactionId → S-edge, different → C-edge
                isSEdge[i] = cleanCurrent.equals(cleanNext);
            } else {
                // Fall back to graph edge type
                EdgeType edgeType = findEdgeType(current, next);
                if (edgeType == null) {
                    return false;
                }
                isSEdge[i] = (edgeType == EdgeType.S_EDGE);
            }
        }
        
        // 3. Verify strict alternation — consecutive same-type edges are invalid
        for (int i = 0; i < cycle.size(); i++) {
            int nextIdx = (i + 1) % cycle.size();
            if (isSEdge[i] == isSEdge[nextIdx]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Finds the edge type between two statements in the adjacency list.
     * 
     * @param from source statement
     * @param to target statement
     * @return the EdgeType, or null if no edge exists
     */
    public EdgeType findEdgeType(SQLStatement from, SQLStatement to) {
        List<Edge> edges = adjacencyList.get(from);
        if (edges != null) {
            for (Edge edge : edges) {
                if (edge.getTarget() == to) {
                    return edge.getType();
                }
            }
        }
        return null;
    }
    
    /**
     * Gets all vertices in the graph.
     * 
     * @return set of all SQL statements
     */
    public Set<SQLStatement> getVertices() {
        return new HashSet<>(vertices);
    }
    
    /**
     * Gets all outgoing edges from a statement.
     * 
     * @param stmt the statement
     * @return list of outgoing edges
     */
    public List<Edge> getOutgoingEdges(SQLStatement stmt) {
        List<Edge> edges = adjacencyList.get(stmt);
        return edges != null ? new ArrayList<>(edges) : new ArrayList<>();
    }
    
    /**
     * Gets all statements belonging to a transaction.
     * 
     * @param transactionId the transaction ID
     * @return list of statements in the transaction
     */
    public List<SQLStatement> getTransactionStatements(String transactionId) {
        List<SQLStatement> statements = transactionStatements.get(transactionId);
        return statements != null ? new ArrayList<>(statements) : new ArrayList<>();
    }
    
    /**
     * Gets the total number of vertices in the graph.
     * 
     * @return number of vertices
     */
    public int getVertexCount() {
        return vertices.size();
    }
    
    /**
     * Gets the total number of edges in the graph.
     * 
     * @return number of edges
     */
    public int getEdgeCount() {
        int count = 0;
        for (List<Edge> edges : adjacencyList.values()) {
            count += edges.size();
        }
        return count;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SCGraph{\n");
        sb.append("  vertices=").append(vertices.size()).append(",\n");
        sb.append("  edges=").append(getEdgeCount()).append(",\n");
        sb.append("  transactions=").append(transactionStatements.size()).append("\n");
        sb.append("}");
        return sb.toString();
    }
}
