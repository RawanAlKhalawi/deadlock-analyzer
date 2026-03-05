package com.rawan.deadlock.report;

import com.rawan.deadlock.model.SQLStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data class representing a single deadlock report.
 * Contains all information about a detected deadlock including the cycle,
 * involved transactions, conflict description, and suggested fix.
 */
public class DeadlockReport {
    
    private int id;
    private List<String> involvedTransactionIds;
    private List<SQLStatement> cycle;
    private String conflictDescription;
    private Map<String, Object> satisfyingAssignment;
    private String suggestedFix;
    
    public DeadlockReport() {
        this.involvedTransactionIds = new ArrayList<>();
        this.cycle = new ArrayList<>();
        this.satisfyingAssignment = new HashMap<>();
    }
    
    public DeadlockReport(int id, List<String> involvedTransactionIds, List<SQLStatement> cycle,
                         String conflictDescription, Map<String, Object> satisfyingAssignment,
                         String suggestedFix) {
        this.id = id;
        this.involvedTransactionIds = involvedTransactionIds != null ? involvedTransactionIds : new ArrayList<>();
        this.cycle = cycle != null ? cycle : new ArrayList<>();
        this.conflictDescription = conflictDescription;
        this.satisfyingAssignment = satisfyingAssignment != null ? satisfyingAssignment : new HashMap<>();
        this.suggestedFix = suggestedFix;
    }
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public List<String> getInvolvedTransactionIds() {
        return involvedTransactionIds;
    }
    
    public void setInvolvedTransactionIds(List<String> involvedTransactionIds) {
        this.involvedTransactionIds = involvedTransactionIds;
    }
    
    public List<SQLStatement> getCycle() {
        return cycle;
    }
    
    public void setCycle(List<SQLStatement> cycle) {
        this.cycle = cycle;
    }
    
    public String getConflictDescription() {
        return conflictDescription;
    }
    
    public void setConflictDescription(String conflictDescription) {
        this.conflictDescription = conflictDescription;
    }
    
    public Map<String, Object> getSatisfyingAssignment() {
        return satisfyingAssignment;
    }
    
    public void setSatisfyingAssignment(Map<String, Object> satisfyingAssignment) {
        this.satisfyingAssignment = satisfyingAssignment;
    }
    
    public String getSuggestedFix() {
        return suggestedFix;
    }
    
    public void setSuggestedFix(String suggestedFix) {
        this.suggestedFix = suggestedFix;
    }
    
    @Override
    public String toString() {
        return "DeadlockReport{" +
                "id=" + id +
                ", involvedTransactionIds=" + involvedTransactionIds +
                ", cycleSize=" + (cycle != null ? cycle.size() : 0) +
                ", conflictDescription='" + conflictDescription + '\'' +
                ", satisfyingAssignment=" + satisfyingAssignment +
                ", suggestedFix='" + suggestedFix + '\'' +
                '}';
    }
}
