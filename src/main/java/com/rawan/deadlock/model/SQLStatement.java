package com.rawan.deadlock.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SQLStatement {
    
    public enum StatementType {
        SELECT,
        UPDATE,
        INSERT,
        DELETE
    }
    
    private StatementType statementType;
    private String queryConditions;
    private Map<String, Object> sqlParameters;
    private int executionOrder;
    
    // New fields for JOIN support and enhanced tracking
    private List<String> tableNames;
    private Map<String, String> tableAliases;
    private String rawSql;
    private String resultReference;
    private List<Map<String, Object>> queryResult;
    private String triggeringCodeLocation;
    private String transactionId;
    
    public SQLStatement() {
        this.sqlParameters = new HashMap<>();
        this.tableNames = new ArrayList<>();
        this.tableAliases = new HashMap<>();
        this.queryResult = new ArrayList<>();
    }
    
    public SQLStatement(StatementType statementType, List<String> tableNames, String queryConditions, int executionOrder) {
        this.statementType = statementType;
        this.queryConditions = queryConditions;
        this.executionOrder = executionOrder;
        this.sqlParameters = new HashMap<>();
        this.tableNames = tableNames != null ? new ArrayList<>(tableNames) : new ArrayList<>();
        this.tableAliases = new HashMap<>();
        this.queryResult = new ArrayList<>();
    }
    
    public StatementType getStatementType() {
        return statementType;
    }
    
    public void setStatementType(StatementType statementType) {
        this.statementType = statementType;
    }
    
    /**
     * @deprecated Use {@link #getTableNames()} instead. This method returns the first table name for backward compatibility.
     */
    @Deprecated
    public String getTableName() {
        return tableNames != null && !tableNames.isEmpty() ? tableNames.get(0) : null;
    }
    
    /**
     * @deprecated Use {@link #setTableNames(List)} or {@link #addTableName(String)} instead.
     */
    @Deprecated
    public void setTableName(String tableName) {
        if (tableName != null) {
            if (this.tableNames == null) {
                this.tableNames = new ArrayList<>();
            }
            if (this.tableNames.isEmpty()) {
                this.tableNames.add(tableName);
            } else {
                this.tableNames.set(0, tableName);
            }
        }
    }
    
    public String getQueryConditions() {
        return queryConditions;
    }
    
    public void setQueryConditions(String queryConditions) {
        this.queryConditions = queryConditions;
    }
    
    public Map<String, Object> getSqlParameters() {
        return sqlParameters;
    }
    
    public void setSqlParameters(Map<String, Object> sqlParameters) {
        this.sqlParameters = sqlParameters;
    }
    
    public void addParameter(String key, Object value) {
        this.sqlParameters.put(key, value);
    }
    
    public int getExecutionOrder() {
        return executionOrder;
    }
    
    public void setExecutionOrder(int executionOrder) {
        this.executionOrder = executionOrder;
    }
    
    public List<String> getTableNames() {
        return tableNames;
    }
    
    public void setTableNames(List<String> tableNames) {
        this.tableNames = tableNames;
    }
    
    public void addTableName(String tableName) {
        this.tableNames.add(tableName);
    }
    
    public Map<String, String> getTableAliases() {
        return tableAliases;
    }
    
    public void setTableAliases(Map<String, String> tableAliases) {
        this.tableAliases = tableAliases;
    }
    
    public void addTableAlias(String alias, String tableName) {
        this.tableAliases.put(alias, tableName);
    }
    
    public String getRawSql() {
        return rawSql;
    }
    
    public void setRawSql(String rawSql) {
        this.rawSql = rawSql;
    }
    
    public String getResultReference() {
        return resultReference;
    }
    
    public void setResultReference(String resultReference) {
        this.resultReference = resultReference;
    }
    
    public List<Map<String, Object>> getQueryResult() {
        return queryResult;
    }
    
    public void setQueryResult(List<Map<String, Object>> queryResult) {
        this.queryResult = queryResult;
    }
    
    public void addQueryResultRow(Map<String, Object> row) {
        this.queryResult.add(row);
    }
    
    public String getTriggeringCodeLocation() {
        return triggeringCodeLocation;
    }
    
    public void setTriggeringCodeLocation(String triggeringCodeLocation) {
        this.triggeringCodeLocation = triggeringCodeLocation;
    }
    
    public String getTransactionId() {
        return transactionId;
    }
    
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
    
    @Override
    public String toString() {
        return "SQLStatement{" +
                "statementType=" + statementType +
                ", tableNames=" + tableNames +
                ", queryConditions='" + queryConditions + '\'' +
                ", sqlParameters=" + sqlParameters +
                ", executionOrder=" + executionOrder +
                ", tableAliases=" + tableAliases +
                ", rawSql='" + rawSql + '\'' +
                ", resultReference='" + resultReference + '\'' +
                ", queryResult=" + (queryResult != null ? queryResult.size() + " rows" : "null") +
                ", triggeringCodeLocation='" + triggeringCodeLocation + '\'' +
                '}';
    }
}
