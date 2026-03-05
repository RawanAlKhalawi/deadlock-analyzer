package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.model.SQLStatement;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IndexAnalyzer infers which database indexes will be used for SQL statements
 * and determines possible index access sequences using topological sort.
 * 
 * Based on MySQL InnoDB index selection and WeSEER methodology.
 */
public class IndexAnalyzer {
    
    /**
     * Represents a database index with its columns and properties.
     */
    public static class Index {
        private String indexName;
        private String tableName;
        private List<String> columns;
        private boolean isPrimary;
        private boolean isUnique;
        
        public Index(String indexName, String tableName, List<String> columns, boolean isPrimary, boolean isUnique) {
            this.indexName = indexName;
            this.tableName = tableName;
            this.columns = columns;
            this.isPrimary = isPrimary;
            this.isUnique = isUnique;
        }
        
        public String getIndexName() {
            return indexName;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public List<String> getColumns() {
            return columns;
        }
        
        public boolean isPrimary() {
            return isPrimary;
        }
        
        public boolean isUnique() {
            return isUnique;
        }
        
        @Override
        public String toString() {
            return "Index{" +
                    "indexName='" + indexName + '\'' +
                    ", tableName='" + tableName + '\'' +
                    ", columns=" + columns +
                    ", isPrimary=" + isPrimary +
                    ", isUnique=" + isUnique +
                    '}';
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Index index = (Index) o;
            return Objects.equals(indexName, index.indexName) &&
                   Objects.equals(tableName, index.tableName);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(indexName, tableName);
        }
    }
    
    /**
     * Represents a directed edge in the index usage graph.
     */
    private static class IndexEdge {
        private Index from;
        private Index to;
        
        public IndexEdge(Index from, Index to) {
            this.from = from;
            this.to = to;
        }
        
        public Index getFrom() {
            return from;
        }
        
        public Index getTo() {
            return to;
        }
    }
    
    // Registry of available indexes per table
    private Map<String, List<Index>> tableIndexes;
    
    // Index usage graph: adjacency list
    private Map<Index, List<Index>> indexGraph;
    
    // SQL parser for robust SQL parsing
    private SQLParser sqlParser;
    
    public IndexAnalyzer() {
        this.tableIndexes = new HashMap<>();
        this.indexGraph = new HashMap<>();
        this.sqlParser = new SQLParser();
    }
    
    /**
     * Registers an index for a table.
     */
    public void registerIndex(Index index) {
        String tableName = index.getTableName();
        tableIndexes.putIfAbsent(tableName, new ArrayList<>());
        tableIndexes.get(tableName).add(index);
    }
    
    /**
     * Infers which indexes will be used for a given SQL statement.
     * Uses heuristics based on MySQL InnoDB optimizer behavior.
     * 
     * @param statement The SQL statement to analyze
     * @return List of indexes that may be used
     */
    public List<Index> inferIndexes(SQLStatement statement) {
        if (statement == null || statement.getTableNames() == null || statement.getTableNames().isEmpty()) {
            return new ArrayList<>();
        }
        
        // For JOIN queries, use the first table for index inference
        String tableName = statement.getTableNames().get(0);
        List<Index> availableIndexes = tableIndexes.get(tableName);
        
        if (availableIndexes == null || availableIndexes.isEmpty()) {
            // No indexes registered, assume primary key exists
            Index defaultPrimary = new Index("PRIMARY", tableName, 
                Arrays.asList("id"), true, true);
            return Arrays.asList(defaultPrimary);
        }
        
        String queryConditions = statement.getQueryConditions();
        if (queryConditions == null || queryConditions.isEmpty()) {
            // No WHERE clause - full table scan or primary key
            return getPrimaryOrFirstIndex(availableIndexes);
        }
        
        // Extract columns mentioned in WHERE clause
        Set<String> conditionColumns = extractColumnsFromConditions(queryConditions);
        
        // Find matching indexes
        List<Index> matchingIndexes = new ArrayList<>();
        
        // Priority 1: Primary key if mentioned
        for (Index index : availableIndexes) {
            if (index.isPrimary() && hasColumnOverlap(index.getColumns(), conditionColumns)) {
                matchingIndexes.add(index);
            }
        }
        
        // Priority 2: Unique indexes if mentioned
        if (matchingIndexes.isEmpty()) {
            for (Index index : availableIndexes) {
                if (index.isUnique() && hasColumnOverlap(index.getColumns(), conditionColumns)) {
                    matchingIndexes.add(index);
                }
            }
        }
        
        // Priority 3: Any index with column overlap
        if (matchingIndexes.isEmpty()) {
            for (Index index : availableIndexes) {
                if (hasColumnOverlap(index.getColumns(), conditionColumns)) {
                    matchingIndexes.add(index);
                }
            }
        }
        
        // If no match found, return primary or first available
        if (matchingIndexes.isEmpty()) {
            return getPrimaryOrFirstIndex(availableIndexes);
        }
        
        return matchingIndexes;
    }
    
    /**
     * Builds an index usage graph showing dependencies between indexes.
     * This represents the order in which indexes might be accessed.
     * 
     * @param statements List of SQL statements to analyze
     */
    public void buildIndexUsageGraph(List<SQLStatement> statements) {
        indexGraph.clear();
        
        if (statements == null || statements.isEmpty()) {
            return;
        }
        
        // For each statement, infer indexes and create edges
        for (int i = 0; i < statements.size(); i++) {
            SQLStatement stmt = statements.get(i);
            List<Index> indexes = inferIndexes(stmt);
            
            // Initialize adjacency list for each index
            for (Index index : indexes) {
                indexGraph.putIfAbsent(index, new ArrayList<>());
            }
            
            // Create edges between indexes of consecutive statements
            if (i > 0) {
                SQLStatement prevStmt = statements.get(i - 1);
                List<Index> prevIndexes = inferIndexes(prevStmt);
                
                for (Index prevIndex : prevIndexes) {
                    for (Index currIndex : indexes) {
                        // Add edge from previous to current
                        indexGraph.putIfAbsent(prevIndex, new ArrayList<>());
                        if (!indexGraph.get(prevIndex).contains(currIndex)) {
                            indexGraph.get(prevIndex).add(currIndex);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Performs topological sort on the index usage graph to find possible
     * index access sequences.
     * 
     * @return List of possible index access sequences (each sequence is a list of indexes)
     */
    public List<List<Index>> findIndexAccessSequences() {
        if (indexGraph.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Perform topological sort using DFS
        Set<Index> visited = new HashSet<>();
        Set<Index> recursionStack = new HashSet<>();
        List<Index> topologicalOrder = new ArrayList<>();
        boolean hasCycle = false;
        
        for (Index index : indexGraph.keySet()) {
            if (!visited.contains(index)) {
                if (topologicalSortDFS(index, visited, recursionStack, topologicalOrder)) {
                    hasCycle = true;
                    break;
                }
            }
        }
        
        // If there's a cycle, return all possible paths
        if (hasCycle) {
            return findAllPaths();
        }
        
        // Reverse to get correct topological order
        Collections.reverse(topologicalOrder);
        
        // Return the topological order as a single sequence
        List<List<Index>> sequences = new ArrayList<>();
        if (!topologicalOrder.isEmpty()) {
            sequences.add(topologicalOrder);
        }
        
        return sequences;
    }
    
    /**
     * DFS helper for topological sort.
     * Returns true if a cycle is detected.
     */
    private boolean topologicalSortDFS(Index index, Set<Index> visited, 
                                       Set<Index> recursionStack, List<Index> result) {
        visited.add(index);
        recursionStack.add(index);
        
        List<Index> neighbors = indexGraph.get(index);
        if (neighbors != null) {
            for (Index neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    if (topologicalSortDFS(neighbor, visited, recursionStack, result)) {
                        return true; // Cycle detected
                    }
                } else if (recursionStack.contains(neighbor)) {
                    return true; // Cycle detected
                }
            }
        }
        
        recursionStack.remove(index);
        result.add(index);
        return false;
    }
    
    /**
     * Finds all possible paths in the index graph (used when cycles exist).
     */
    private List<List<Index>> findAllPaths() {
        List<List<Index>> allPaths = new ArrayList<>();
        
        // Find all source nodes (nodes with no incoming edges)
        Set<Index> allNodes = new HashSet<>(indexGraph.keySet());
        Set<Index> nodesWithIncoming = new HashSet<>();
        
        for (List<Index> neighbors : indexGraph.values()) {
            nodesWithIncoming.addAll(neighbors);
        }
        
        Set<Index> sourceNodes = new HashSet<>(allNodes);
        sourceNodes.removeAll(nodesWithIncoming);
        
        // If no source nodes, pick any node
        if (sourceNodes.isEmpty() && !allNodes.isEmpty()) {
            sourceNodes.add(allNodes.iterator().next());
        }
        
        // DFS from each source to find all paths
        for (Index source : sourceNodes) {
            List<Index> currentPath = new ArrayList<>();
            Set<Index> visited = new HashSet<>();
            findPathsDFS(source, currentPath, visited, allPaths);
        }
        
        return allPaths;
    }
    
    /**
     * DFS helper to find all paths from a source node.
     */
    private void findPathsDFS(Index current, List<Index> currentPath, 
                             Set<Index> visited, List<List<Index>> allPaths) {
        currentPath.add(current);
        visited.add(current);
        
        List<Index> neighbors = indexGraph.get(current);
        boolean hasUnvisitedNeighbor = false;
        
        if (neighbors != null) {
            for (Index neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    hasUnvisitedNeighbor = true;
                    findPathsDFS(neighbor, currentPath, visited, allPaths);
                }
            }
        }
        
        // If no unvisited neighbors, this is a complete path
        if (!hasUnvisitedNeighbor) {
            allPaths.add(new ArrayList<>(currentPath));
        }
        
        // Backtrack
        currentPath.remove(currentPath.size() - 1);
        visited.remove(current);
    }
    
    /**
     * Extracts column names from WHERE clause conditions.
     * Uses SQLParser for more robust parsing.
     */
    private Set<String> extractColumnsFromConditions(String conditions) {
        Set<String> columns = new HashSet<>();
        
        // Use SQLParser to extract columns
        List<String> columnList = sqlParser.extractColumnsFromWhere(conditions);
        for (String col : columnList) {
            columns.add(col.toLowerCase());
        }
        
        return columns;
    }
    
    /**
     * Checks if a string is a common SQL keyword.
     */
    private boolean isSQLKeyword(String word) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
            "and", "or", "not", "null", "true", "false", "is", "in", "like", 
            "between", "exists", "all", "any", "some"
        ));
        return keywords.contains(word.toLowerCase());
    }
    
    /**
     * Checks if there's any overlap between index columns and condition columns.
     */
    private boolean hasColumnOverlap(List<String> indexColumns, Set<String> conditionColumns) {
        for (String indexCol : indexColumns) {
            if (conditionColumns.contains(indexCol.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Returns the primary index or the first available index.
     */
    private List<Index> getPrimaryOrFirstIndex(List<Index> indexes) {
        for (Index index : indexes) {
            if (index.isPrimary()) {
                return Arrays.asList(index);
            }
        }
        return Arrays.asList(indexes.get(0));
    }
    
    /**
     * Gets all registered indexes for a table.
     */
    public List<Index> getTableIndexes(String tableName) {
        return tableIndexes.getOrDefault(tableName, new ArrayList<>());
    }
    
    /**
     * Gets the index usage graph.
     */
    public Map<Index, List<Index>> getIndexGraph() {
        return indexGraph;
    }
}
