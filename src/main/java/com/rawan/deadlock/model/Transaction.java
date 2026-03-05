package com.rawan.deadlock.model;

import java.util.ArrayList;
import java.util.List;

public class Transaction {
    
    private String transactionId;
    private List<SQLStatement> sqlStatements;
    private List<String> pathConditions;
    private Long beginTimestamp;
    private Long commitTimestamp;
    private Long abortTimestamp;
    
    public Transaction() {
        this.sqlStatements = new ArrayList<>();
        this.pathConditions = new ArrayList<>();
    }
    
    public Transaction(String transactionId) {
        this.transactionId = transactionId;
        this.sqlStatements = new ArrayList<>();
        this.pathConditions = new ArrayList<>();
    }
    
    public String getTransactionId() {
        return transactionId;
    }
    
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
    
    public List<SQLStatement> getSqlStatements() {
        return sqlStatements;
    }
    
    public void setSqlStatements(List<SQLStatement> sqlStatements) {
        this.sqlStatements = sqlStatements;
    }
    
    public void addSqlStatement(SQLStatement statement) {
        if (statement != null && this.transactionId != null) {
            statement.setTransactionId(this.transactionId);
        }
        this.sqlStatements.add(statement);
    }
    
    public List<String> getPathConditions() {
        return pathConditions;
    }
    
    public void setPathConditions(List<String> pathConditions) {
        this.pathConditions = pathConditions;
    }
    
    public void addPathCondition(String condition) {
        this.pathConditions.add(condition);
    }
    
    public Long getBeginTimestamp() {
        return beginTimestamp;
    }
    
    public void setBeginTimestamp(Long beginTimestamp) {
        this.beginTimestamp = beginTimestamp;
    }
    
    public Long getCommitTimestamp() {
        return commitTimestamp;
    }
    
    public void setCommitTimestamp(Long commitTimestamp) {
        this.commitTimestamp = commitTimestamp;
    }
    
    public Long getAbortTimestamp() {
        return abortTimestamp;
    }
    
    public void setAbortTimestamp(Long abortTimestamp) {
        this.abortTimestamp = abortTimestamp;
    }
    
    public boolean isCommitted() {
        return commitTimestamp != null;
    }
    
    public boolean isAborted() {
        return abortTimestamp != null;
    }
    
    @Override
    public String toString() {
        return "Transaction{" +
                "transactionId='" + transactionId + '\'' +
                ", sqlStatements=" + sqlStatements.size() +
                ", pathConditions=" + pathConditions.size() +
                ", beginTimestamp=" + beginTimestamp +
                ", commitTimestamp=" + commitTimestamp +
                ", abortTimestamp=" + abortTimestamp +
                '}';
    }
}
