package com.rawan.deadlock.loader;

import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class TraceLoaderTest {
    
    private TraceLoader loader;
    private String testResourcesDir;
    
    @Before
    public void setUp() {
        loader = new TraceLoader();
        testResourcesDir = "src/test/resources/sample-traces";
    }
    
    // ========== Valid File Loading Tests ==========
    
    @Test
    public void testLoadValidJsonFile() {
        String filePath = testResourcesDir + "/finishOrder-trace.json";
        List<Transaction> transactions = loader.loadFromFile(filePath);
        
        assertNotNull("Transactions list should not be null", transactions);
        assertEquals("Should load 2 transactions", 2, transactions.size());
        
        // Verify first transaction
        Transaction tx1 = transactions.get(0);
        assertEquals("TX1", tx1.getTransactionId());
        assertEquals(2, tx1.getSqlStatements().size());
        assertEquals(1, tx1.getPathConditions().size());
        assertEquals("true", tx1.getPathConditions().get(0));
    }
    
    @Test
    public void testLoadFromDirectory() {
        List<Transaction> transactions = loader.loadFromDirectory(testResourcesDir);
        
        assertNotNull("Transactions list should not be null", transactions);
        assertTrue("Should load at least 2 transactions", transactions.size() >= 2);
    }
    
    // ========== Statement Field Verification Tests ==========
    
    @Test
    public void testStatementFieldsPopulatedCorrectly() {
        String filePath = testResourcesDir + "/finishOrder-trace.json";
        List<Transaction> transactions = loader.loadFromFile(filePath);
        
        assertFalse("Transactions should not be empty", transactions.isEmpty());
        
        Transaction tx = transactions.get(0);
        assertFalse("Transaction should have statements", tx.getSqlStatements().isEmpty());
        
        SQLStatement stmt = tx.getSqlStatements().get(0);
        
        // Verify all fields are populated
        assertNotNull("Statement type should not be null", stmt.getStatementType());
        assertEquals("Statement type should be SELECT", SQLStatement.StatementType.SELECT, stmt.getStatementType());
        
        assertNotNull("Table names should not be null", stmt.getTableNames());
        assertEquals("Should have 3 table names", 3, stmt.getTableNames().size());
        assertTrue("Table names should contain OrderItem", stmt.getTableNames().contains("OrderItem"));
        assertTrue("Table names should contain Order", stmt.getTableNames().contains("Order"));
        assertTrue("Table names should contain Product", stmt.getTableNames().contains("Product"));
        
        assertNotNull("Query conditions should not be null", stmt.getQueryConditions());
        assertTrue("Query conditions should contain O_ID", stmt.getQueryConditions().contains("O_ID"));
        
        assertEquals("Execution order should be 1", 1, stmt.getExecutionOrder());
        
        assertNotNull("Raw SQL should not be null", stmt.getRawSql());
        assertTrue("Raw SQL should contain SELECT", stmt.getRawSql().contains("SELECT"));
        
        assertNotNull("Result reference should not be null", stmt.getResultReference());
        assertEquals("Result reference should be res1", "res1", stmt.getResultReference());
        
        assertNotNull("Triggering code location should not be null", stmt.getTriggeringCodeLocation());
        assertTrue("Triggering code location should contain finishOrder", 
                   stmt.getTriggeringCodeLocation().contains("finishOrder"));
        
        assertNotNull("Parameters should not be null", stmt.getSqlParameters());
        assertFalse("Parameters should not be empty", stmt.getSqlParameters().isEmpty());
        assertTrue("Parameters should contain O_ID", stmt.getSqlParameters().containsKey("O_ID"));
        
        assertNotNull("Table names should not be null", stmt.getTableNames());
        assertEquals("Should have 3 table names", 3, stmt.getTableNames().size());
        
        assertNotNull("Table aliases should not be null", stmt.getTableAliases());
        assertEquals("Should have 3 table aliases", 3, stmt.getTableAliases().size());
        assertEquals("Alias 'oi' should map to OrderItem", "OrderItem", stmt.getTableAliases().get("oi"));
    }
    
    @Test
    public void testPathConditionsLoadedCorrectly() {
        String filePath = testResourcesDir + "/finishOrder-trace.json";
        List<Transaction> transactions = loader.loadFromFile(filePath);
        
        assertFalse("Transactions should not be empty", transactions.isEmpty());
        
        Transaction tx = transactions.get(0);
        assertNotNull("Path conditions should not be null", tx.getPathConditions());
        assertFalse("Path conditions should not be empty", tx.getPathConditions().isEmpty());
        assertEquals("Should have 1 path condition", 1, tx.getPathConditions().size());
        assertEquals("Path condition should be 'true'", "true", tx.getPathConditions().get(0));
    }
    
    @Test
    public void testTimestampsLoadedCorrectly() {
        String filePath = testResourcesDir + "/finishOrder-trace.json";
        List<Transaction> transactions = loader.loadFromFile(filePath);
        
        assertFalse("Transactions should not be empty", transactions.isEmpty());
        
        Transaction tx = transactions.get(0);
        assertNotNull("Begin timestamp should not be null", tx.getBeginTimestamp());
        assertEquals("Begin timestamp should be 1000000000", Long.valueOf(1000000000L), tx.getBeginTimestamp());
        
        assertNotNull("Commit timestamp should not be null", tx.getCommitTimestamp());
        assertEquals("Commit timestamp should be 1000000100", Long.valueOf(1000000100L), tx.getCommitTimestamp());
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    public void testLoadMissingFile() {
        List<Transaction> transactions = loader.loadFromFile("nonexistent.json");
        
        assertNotNull("Should return empty list for missing file", transactions);
        assertTrue("Should return empty list for missing file", transactions.isEmpty());
    }
    
    @Test
    public void testLoadMalformedJson() throws IOException {
        // Create a temporary malformed JSON file
        File tempFile = File.createTempFile("malformed", ".json");
        tempFile.deleteOnExit();
        
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("{\"transactions\": [invalid json}");
        }
        
        List<Transaction> transactions = loader.loadFromFile(tempFile.getAbsolutePath());
        
        assertNotNull("Should return empty list for malformed JSON", transactions);
        assertTrue("Should return empty list for malformed JSON", transactions.isEmpty());
    }
    
    @Test
    public void testLoadEmptyDirectory() throws IOException {
        // Create a temporary empty directory
        File tempDir = File.createTempFile("empty", "dir");
        tempDir.delete();
        tempDir.mkdir();
        tempDir.deleteOnExit();
        
        List<Transaction> transactions = loader.loadFromDirectory(tempDir.getAbsolutePath());
        
        assertNotNull("Should return empty list for empty directory", transactions);
        assertTrue("Should return empty list for empty directory", transactions.isEmpty());
    }
    
    @Test
    public void testLoadNullFilePath() {
        List<Transaction> transactions = loader.loadFromFile(null);
        
        assertNotNull("Should return empty list for null file path", transactions);
        assertTrue("Should return empty list for null file path", transactions.isEmpty());
    }
    
    @Test
    public void testLoadEmptyFilePath() {
        List<Transaction> transactions = loader.loadFromFile("");
        
        assertNotNull("Should return empty list for empty file path", transactions);
        assertTrue("Should return empty list for empty file path", transactions.isEmpty());
    }
    
    @Test
    public void testLoadNullDirectoryPath() {
        List<Transaction> transactions = loader.loadFromDirectory(null);
        
        assertNotNull("Should return empty list for null directory path", transactions);
        assertTrue("Should return empty list for null directory path", transactions.isEmpty());
    }
    
    @Test
    public void testLoadNonExistentDirectory() {
        List<Transaction> transactions = loader.loadFromDirectory("/nonexistent/directory");
        
        assertNotNull("Should return empty list for non-existent directory", transactions);
        assertTrue("Should return empty list for non-existent directory", transactions.isEmpty());
    }
    
    // ========== Multiple Files Test ==========
    
    @Test
    public void testLoadMultipleFilesFromDirectory() throws IOException {
        // Create a temporary directory with multiple JSON files
        File tempDir = File.createTempFile("multi", "dir");
        tempDir.delete();
        tempDir.mkdir();
        tempDir.deleteOnExit();
        
        // Create first JSON file
        File file1 = new File(tempDir, "trace1.json");
        file1.deleteOnExit();
        try (FileWriter writer = new FileWriter(file1)) {
            writer.write("{\"transactions\": [{\"id\": \"TX1\", \"statements\": [], \"pathConditions\": []}]}");
        }
        
        // Create second JSON file
        File file2 = new File(tempDir, "trace2.json");
        file2.deleteOnExit();
        try (FileWriter writer = new FileWriter(file2)) {
            writer.write("{\"transactions\": [{\"id\": \"TX2\", \"statements\": [], \"pathConditions\": []}]}");
        }
        
        List<Transaction> transactions = loader.loadFromDirectory(tempDir.getAbsolutePath());
        
        assertNotNull("Transactions list should not be null", transactions);
        assertEquals("Should load 2 transactions from 2 files", 2, transactions.size());
    }
    
    // ========== Edge Cases Tests ==========
    
    @Test
    public void testLoadFileWithNoTransactions() throws IOException {
        File tempFile = File.createTempFile("empty", ".json");
        tempFile.deleteOnExit();
        
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("{\"transactions\": []}");
        }
        
        List<Transaction> transactions = loader.loadFromFile(tempFile.getAbsolutePath());
        
        assertNotNull("Should return empty list", transactions);
        assertTrue("Should return empty list when no transactions in JSON", transactions.isEmpty());
    }
    
    @Test
    public void testLoadFileWithInvalidStatementType() throws IOException {
        File tempFile = File.createTempFile("invalid", ".json");
        tempFile.deleteOnExit();
        
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("{\"transactions\": [{\"id\": \"TX1\", \"statements\": [{\"type\": \"INVALID_TYPE\"}], \"pathConditions\": []}]}");
        }
        
        List<Transaction> transactions = loader.loadFromFile(tempFile.getAbsolutePath());
        
        assertNotNull("Should return list", transactions);
        assertEquals("Should load 1 transaction", 1, transactions.size());
        // Invalid statement should be skipped
        assertTrue("Transaction should have no valid statements", transactions.get(0).getSqlStatements().isEmpty());
    }
    
    @Test
    public void testLoadFileAsDirectory() {
        String filePath = testResourcesDir + "/finishOrder-trace.json";
        List<Transaction> transactions = loader.loadFromDirectory(filePath);
        
        assertNotNull("Should return empty list when file is passed as directory", transactions);
        assertTrue("Should return empty list when file is passed as directory", transactions.isEmpty());
    }
    
    @Test
    public void testLoadDirectoryAsFile() {
        List<Transaction> transactions = loader.loadFromFile(testResourcesDir);
        
        assertNotNull("Should return empty list when directory is passed as file", transactions);
        assertTrue("Should return empty list when directory is passed as file", transactions.isEmpty());
    }
}
