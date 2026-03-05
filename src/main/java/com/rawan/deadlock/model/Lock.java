package com.rawan.deadlock.model;

import java.util.ArrayList;
import java.util.List;

public class Lock {
    
    public enum LockGranularity {
        ROW,
        RANGE,
        TABLE
    }
    
    public enum LockMode {
        SHARED,
        EXCLUSIVE
    }
    
    private String databaseIndex;
    private LockGranularity lockGranularity;
    private LockMode lockMode;
    private List<String> predicates;
    
    public Lock() {
        this.predicates = new ArrayList<>();
    }
    
    public Lock(String databaseIndex, LockGranularity lockGranularity, LockMode lockMode) {
        this.databaseIndex = databaseIndex;
        this.lockGranularity = lockGranularity;
        this.lockMode = lockMode;
        this.predicates = new ArrayList<>();
    }
    
    public String getDatabaseIndex() {
        return databaseIndex;
    }
    
    public void setDatabaseIndex(String databaseIndex) {
        this.databaseIndex = databaseIndex;
    }
    
    public LockGranularity getLockGranularity() {
        return lockGranularity;
    }
    
    public void setLockGranularity(LockGranularity lockGranularity) {
        this.lockGranularity = lockGranularity;
    }
    
    public LockMode getLockMode() {
        return lockMode;
    }
    
    public void setLockMode(LockMode lockMode) {
        this.lockMode = lockMode;
    }
    
    public List<String> getPredicates() {
        return predicates;
    }
    
    public void setPredicates(List<String> predicates) {
        this.predicates = predicates;
    }
    
    public void addPredicate(String predicate) {
        this.predicates.add(predicate);
    }
    
    @Override
    public String toString() {
        return "Lock{" +
                "databaseIndex='" + databaseIndex + '\'' +
                ", lockGranularity=" + lockGranularity +
                ", lockMode=" + lockMode +
                ", predicates=" + predicates +
                '}';
    }
}
