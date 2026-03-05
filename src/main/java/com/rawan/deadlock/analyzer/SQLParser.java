package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.model.SQLStatement;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.schema.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized SQL parser using JSQLParser library.
 * Parses raw SQL strings into SQLStatement objects, supporting:
 * - SELECT with JOINs
 * - UPDATE
 * - INSERT
 * - DELETE
 * 
 * Follows WeSEER paper Figure 6 syntax.
 */
public class SQLParser {
    
    private static final Logger logger = LoggerFactory.getLogger(SQLParser.class);
    
    /**
     * Parses a raw SQL string into a SQLStatement object.
     * Populates: statementType, tableName, tableNames, tableAliases, queryConditions, rawSql
     * 
     * @param rawSql Raw SQL string to parse
     * @return SQLStatement object with parsed information
     * @throws IllegalArgumentException if SQL is null, empty, or cannot be parsed
     */
    public SQLStatement parse(String rawSql) {
        if (rawSql == null || rawSql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL string cannot be null or empty");
        }
        
        logger.debug("Parsing SQL: {}", rawSql);
        
        try {
            Statement statement = CCJSqlParserUtil.parse(rawSql);
            SQLStatement sqlStatement = new SQLStatement();
            sqlStatement.setRawSql(rawSql);
            
            if (statement instanceof Select) {
                parseSelect((Select) statement, sqlStatement);
            } else if (statement instanceof Update) {
                parseUpdate((Update) statement, sqlStatement);
            } else if (statement instanceof Insert) {
                parseInsert((Insert) statement, sqlStatement);
            } else if (statement instanceof Delete) {
                parseDelete((Delete) statement, sqlStatement);
            } else {
                throw new IllegalArgumentException("Unsupported SQL statement type: " + statement.getClass().getName());
            }
            
            logger.debug("Parsed SQL statement: {}", sqlStatement);
            return sqlStatement;
            
        } catch (JSQLParserException e) {
            logger.error("Failed to parse SQL: {}", rawSql, e);
            throw new IllegalArgumentException("Failed to parse SQL: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parses a SELECT statement.
     * Supports JOINs and extracts table names, aliases, and WHERE conditions.
     */
    private void parseSelect(Select select, SQLStatement sqlStatement) {
        sqlStatement.setStatementType(SQLStatement.StatementType.SELECT);
        
        // In JSQLParser 4.9, Select can be PlainSelect, SetOperationList, etc.
        if (select instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) select;
            
            // Extract FROM table
            FromItem fromItem = plainSelect.getFromItem();
            if (fromItem != null) {
                extractTableInfo(fromItem, sqlStatement, true);
            }
            
            // Extract JOIN tables
            List<Join> joins = plainSelect.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    FromItem joinItem = join.getRightItem();
                    if (joinItem != null) {
                        extractTableInfo(joinItem, sqlStatement, false);
                    }
                }
            }
            
            // Extract WHERE clause
            Expression where = plainSelect.getWhere();
            if (where != null) {
                sqlStatement.setQueryConditions(where.toString());
            }
        } else if (select instanceof SetOperationList) {
            // Handle UNION, INTERSECT, EXCEPT
            logger.warn("Set operations (UNION, INTERSECT, EXCEPT) are not fully supported");
            sqlStatement.setQueryConditions("SET_OPERATION");
        } else if (select instanceof ParenthesedSelect) {
            // Handle parenthesed select
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            parseSelect(parenthesedSelect.getSelect(), sqlStatement);
        }
    }
    
    /**
     * Parses an UPDATE statement.
     */
    private void parseUpdate(Update update, SQLStatement sqlStatement) {
        sqlStatement.setStatementType(SQLStatement.StatementType.UPDATE);
        
        // Extract table name
        if (update.getTable() != null) {
            String tableName = update.getTable().getName();
            sqlStatement.addTableName(tableName);
            
            // Extract alias if present
            if (update.getTable().getAlias() != null) {
                String alias = update.getTable().getAlias().getName();
                sqlStatement.addTableAlias(alias, tableName);
            }
        }
        
        // Extract WHERE clause
        Expression where = update.getWhere();
        if (where != null) {
            sqlStatement.setQueryConditions(where.toString());
        }
    }
    
    /**
     * Parses an INSERT statement.
     */
    private void parseInsert(Insert insert, SQLStatement sqlStatement) {
        sqlStatement.setStatementType(SQLStatement.StatementType.INSERT);
        
        // Extract table name
        if (insert.getTable() != null) {
            String tableName = insert.getTable().getName();
            sqlStatement.addTableName(tableName);
            
            // Extract alias if present
            if (insert.getTable().getAlias() != null) {
                String alias = insert.getTable().getAlias().getName();
                sqlStatement.addTableAlias(alias, tableName);
            }
        }
        
        // INSERT doesn't have WHERE clause, but we can note it's an insert
        sqlStatement.setQueryConditions("INSERT");
    }
    
    /**
     * Parses a DELETE statement.
     */
    private void parseDelete(Delete delete, SQLStatement sqlStatement) {
        sqlStatement.setStatementType(SQLStatement.StatementType.DELETE);
        
        // Extract table name
        if (delete.getTable() != null) {
            String tableName = delete.getTable().getName();
            sqlStatement.addTableName(tableName);
            
            // Extract alias if present
            if (delete.getTable().getAlias() != null) {
                String alias = delete.getTable().getAlias().getName();
                sqlStatement.addTableAlias(alias, tableName);
            }
        }
        
        // Extract WHERE clause
        Expression where = delete.getWhere();
        if (where != null) {
            sqlStatement.setQueryConditions(where.toString());
        }
    }
    
    /**
     * Extracts table information from a FromItem (table or subquery).
     * 
     * @param fromItem The FROM item to extract from
     * @param sqlStatement The SQLStatement to populate
     * @param isPrimaryTable Whether this is the primary table (first in FROM clause)
     */
    private void extractTableInfo(FromItem fromItem, SQLStatement sqlStatement, boolean isPrimaryTable) {
        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            String tableName = table.getName();
            
            // Add to tableNames list
            sqlStatement.addTableName(tableName);
            
            // Extract alias if present
            if (table.getAlias() != null) {
                String alias = table.getAlias().getName();
                sqlStatement.addTableAlias(alias, tableName);
            }
        } else if (fromItem instanceof ParenthesedSelect) {
            // Handle subqueries (ParenthesedSelect in JSQLParser 4.9)
            logger.debug("Subquery detected in FROM clause");
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            if (parenthesedSelect.getAlias() != null) {
                String alias = parenthesedSelect.getAlias().getName();
                sqlStatement.addTableAlias(alias, "SUBQUERY");
            }
        } else if (fromItem instanceof LateralSubSelect) {
            // Handle lateral subqueries
            logger.debug("Lateral subquery detected in FROM clause");
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getAlias() != null) {
                String alias = lateralSubSelect.getAlias().getName();
                sqlStatement.addTableAlias(alias, "LATERAL_SUBQUERY");
            }
        }
    }
    
    /**
     * Extracts column names from a WHERE clause expression.
     * Useful for index analysis and lock generation.
     * 
     * @param whereClause WHERE clause as string
     * @return List of column names mentioned in the WHERE clause
     */
    public List<String> extractColumnsFromWhere(String whereClause) {
        List<String> columns = new ArrayList<>();
        
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return columns;
        }
        
        try {
            // Parse as a simple condition expression
            Expression expr = CCJSqlParserUtil.parseCondExpression(whereClause);
            ColumnExtractor extractor = new ColumnExtractor();
            columns = extractor.extractColumns(expr);
        } catch (JSQLParserException e) {
            logger.warn("Failed to extract columns from WHERE clause: {}", whereClause, e);
        }
        
        return columns;
    }
    
    /**
     * Helper class to extract column names from expressions.
     */
    private static class ColumnExtractor {
        
        public List<String> extractColumns(Expression expr) {
            List<String> columns = new ArrayList<>();
            
            if (expr == null) {
                return columns;
            }
            
            // Use JSQLParser's expression visitor pattern
            // For simplicity, convert to string and use regex
            String exprStr = expr.toString();
            
            // Simple regex to extract identifiers before operators
            // This is a simplified approach; full implementation would use ExpressionVisitor
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=|<|>|<=|>=|!=|<>|LIKE|IN|BETWEEN)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pattern.matcher(exprStr);
            
            while (matcher.find()) {
                String column = matcher.group(1);
                if (!isSQLKeyword(column)) {
                    columns.add(column);
                }
            }
            
            return columns;
        }
        
        private boolean isSQLKeyword(String word) {
            java.util.Set<String> keywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "AND", "OR", "NOT", "NULL", "TRUE", "FALSE", "IS", "IN", "LIKE",
                "BETWEEN", "EXISTS", "ALL", "ANY", "SOME", "SELECT", "FROM", "WHERE"
            ));
            return keywords.contains(word.toUpperCase());
        }
    }
}
