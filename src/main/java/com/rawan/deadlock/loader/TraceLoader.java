package com.rawan.deadlock.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rawan.deadlock.model.SQLStatement;
import com.rawan.deadlock.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TraceLoader loads pre-extracted transactions from JSON files.
 * Supports loading from individual files or entire directories.
 */
public class TraceLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(TraceLoader.class);
    private final ObjectMapper objectMapper;
    
    public TraceLoader() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Loads transactions from a single JSON file.
     * Expected JSON format:
     * {
     *   "transactions": [
     *     {
     *       "id": "tx1",
     *       "statements": [
     *         {
     *           "type": "SELECT",
     *           "rawSql": "SELECT * FROM Product WHERE ID=?",
     *           "tableName": "Product",
     *           "queryConditions": "ID=?",
     *           "executionOrder": 1,
     *           "parameters": {"ID": "sym_1"}
     *         }
     *       ],
     *       "pathConditions": ["sym_1 > 0"]
     *     }
     *   ]
     * }
     * 
     * @param filePath Path to JSON file
     * @return List of Transaction objects
     */
    public List<Transaction> loadFromFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            logger.warn("File path is null or empty");
            return new ArrayList<>();
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            logger.warn("File does not exist: {}", filePath);
            return new ArrayList<>();
        }
        
        if (!file.isFile()) {
            logger.warn("Path is not a file: {}", filePath);
            return new ArrayList<>();
        }
        
        try {
            logger.info("Loading transactions from file: {}", filePath);
            JsonNode root = objectMapper.readTree(file);
            
            List<Transaction> transactions = parseTransactions(root);
            logger.info("Loaded {} transactions from {}", transactions.size(), filePath);
            
            return transactions;
        } catch (IOException e) {
            logger.error("Failed to load transactions from file: {}", filePath, e);
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Error parsing JSON from file: {}", filePath, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Loads transactions from all JSON files in a directory.
     * 
     * @param dirPath Path to directory containing JSON files
     * @return List of all Transaction objects from all files
     */
    public List<Transaction> loadFromDirectory(String dirPath) {
        if (dirPath == null || dirPath.trim().isEmpty()) {
            logger.warn("Directory path is null or empty");
            return new ArrayList<>();
        }
        
        File dir = new File(dirPath);
        if (!dir.exists()) {
            logger.warn("Directory does not exist: {}", dirPath);
            return new ArrayList<>();
        }
        
        if (!dir.isDirectory()) {
            logger.warn("Path is not a directory: {}", dirPath);
            return new ArrayList<>();
        }
        
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
        
        if (files == null || files.length == 0) {
            logger.warn("No JSON files found in directory: {}", dirPath);
            return new ArrayList<>();
        }
        
        logger.info("Found {} JSON files in directory: {}", files.length, dirPath);
        
        List<Transaction> allTransactions = new ArrayList<>();
        for (File file : files) {
            List<Transaction> transactions = loadFromFile(file.getAbsolutePath());
            allTransactions.addAll(transactions);
        }
        
        logger.info("Loaded total {} transactions from directory: {}", allTransactions.size(), dirPath);
        
        return allTransactions;
    }
    
    /**
     * Parses transactions from JSON root node.
     */
    private List<Transaction> parseTransactions(JsonNode root) {
        List<Transaction> transactions = new ArrayList<>();
        
        if (root == null || !root.has("transactions")) {
            logger.warn("JSON root does not contain 'transactions' field");
            return transactions;
        }
        
        JsonNode transactionsNode = root.get("transactions");
        if (!transactionsNode.isArray()) {
            logger.warn("'transactions' field is not an array");
            return transactions;
        }
        
        for (JsonNode txNode : transactionsNode) {
            try {
                Transaction tx = parseTransaction(txNode);
                if (tx != null) {
                    transactions.add(tx);
                }
            } catch (Exception e) {
                logger.error("Error parsing transaction: {}", e.getMessage());
            }
        }
        
        return transactions;
    }
    
    /**
     * Parses a single transaction from JSON node.
     */
    private Transaction parseTransaction(JsonNode txNode) {
        if (txNode == null) {
            return null;
        }
        
        // Get transaction ID
        String id = txNode.has("id") ? txNode.get("id").asText() : null;
        Transaction tx = new Transaction(id);
        
        // Parse statements
        if (txNode.has("statements") && txNode.get("statements").isArray()) {
            for (JsonNode stmtNode : txNode.get("statements")) {
                SQLStatement stmt = parseStatement(stmtNode);
                if (stmt != null) {
                    stmt.setTransactionId(id);
                    tx.addSqlStatement(stmt);
                }
            }
        }
        
        // Parse path conditions
        if (txNode.has("pathConditions") && txNode.get("pathConditions").isArray()) {
            for (JsonNode pcNode : txNode.get("pathConditions")) {
                String pathCondition = pcNode.asText();
                if (pathCondition != null && !pathCondition.trim().isEmpty()) {
                    tx.addPathCondition(pathCondition);
                }
            }
        }
        
        // Parse timestamps if present
        if (txNode.has("beginTimestamp")) {
            tx.setBeginTimestamp(txNode.get("beginTimestamp").asLong());
        }
        if (txNode.has("commitTimestamp")) {
            tx.setCommitTimestamp(txNode.get("commitTimestamp").asLong());
        }
        if (txNode.has("abortTimestamp")) {
            tx.setAbortTimestamp(txNode.get("abortTimestamp").asLong());
        }
        
        return tx;
    }
    
    /**
     * Parses a single SQL statement from JSON node.
     */
    private SQLStatement parseStatement(JsonNode stmtNode) {
        if (stmtNode == null) {
            return null;
        }
        
        SQLStatement stmt = new SQLStatement();
        
        // Parse statement type
        if (stmtNode.has("type")) {
            String type = stmtNode.get("type").asText().toUpperCase();
            try {
                stmt.setStatementType(SQLStatement.StatementType.valueOf(type));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid statement type: {}", type);
                return null;
            }
        }
        
        // Parse query conditions
        if (stmtNode.has("queryConditions")) {
            stmt.setQueryConditions(stmtNode.get("queryConditions").asText());
        }
        
        // Parse execution order
        if (stmtNode.has("executionOrder")) {
            stmt.setExecutionOrder(stmtNode.get("executionOrder").asInt());
        }
        
        // Parse raw SQL
        if (stmtNode.has("rawSql")) {
            stmt.setRawSql(stmtNode.get("rawSql").asText());
        }
        
        // Parse result reference
        if (stmtNode.has("resultReference")) {
            stmt.setResultReference(stmtNode.get("resultReference").asText());
        }
        
        // Parse triggering code location
// Parse triggering code location
        if (stmtNode.has("triggeringCodeLocation")) {
            String loc = stmtNode.get("triggeringCodeLocation").asText();
            stmt.setTriggeringCodeLocation(loc);

            // Primary: read dedicated lineNumber field
            int lineNum = stmtNode.path("lineNumber").asInt(0);

            // Fallback: parse "(line N)" embedded in the string by buildTriggeringLocation.
            // This handles the case where DeadlockAnalyzer creates new SQLStatement objects
            // that don't copy the lineNumber field from the loaded transaction.
            if (lineNum == 0 && loc != null && !loc.isBlank()) {
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\(line (\\d+)\\)\\s*$").matcher(loc);
                if (m.find()) {
                    lineNum = Integer.parseInt(m.group(1));
                }
            }
            stmt.setLineNumber(lineNum);
        }

        // Parse parameters
        if (stmtNode.has("parameters") && stmtNode.get("parameters").isObject()) {
            JsonNode paramsNode = stmtNode.get("parameters");
            Iterator<Map.Entry<String, JsonNode>> fields = paramsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode valueNode = entry.getValue();
                Object value;
                
                if (valueNode.isInt()) {
                    value = valueNode.asInt();
                } else if (valueNode.isLong()) {
                    value = valueNode.asLong();
                } else if (valueNode.isDouble()) {
                    value = valueNode.asDouble();
                } else if (valueNode.isBoolean()) {
                    value = valueNode.asBoolean();
                } else {
                    value = valueNode.asText();
                }
                
                stmt.addParameter(key, value);
            }
        }
        
        // Parse table names (for JOIN support)
        if (stmtNode.has("tableNames") && stmtNode.get("tableNames").isArray()) {
            for (JsonNode tableNode : stmtNode.get("tableNames")) {
                stmt.addTableName(tableNode.asText());
            }
        } else if (stmtNode.has("tableName")) {
            // Backward compatibility: if old JSON has "tableName" but not "tableNames",
            // create tableNames = [tableName]
            String tableName = stmtNode.get("tableName").asText();
            if (tableName != null && !tableName.isEmpty()) {
                stmt.addTableName(tableName);
            }
        }
        
        // Parse table aliases
        if (stmtNode.has("tableAliases") && stmtNode.get("tableAliases").isObject()) {
            JsonNode aliasesNode = stmtNode.get("tableAliases");
            Iterator<Map.Entry<String, JsonNode>> fields = aliasesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                stmt.addTableAlias(entry.getKey(), entry.getValue().asText());
            }
        }
        
        return stmt;
    }
}
