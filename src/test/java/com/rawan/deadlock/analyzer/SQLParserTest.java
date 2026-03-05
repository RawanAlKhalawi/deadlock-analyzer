package com.rawan.deadlock.analyzer;

import com.rawan.deadlock.model.SQLStatement;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SQLParserTest {
    
    private SQLParser parser;
    
    @Before
    public void setUp() {
        parser = new SQLParser();
    }
    
    // ========== Constructor and Basic Tests ==========
    
    @Test
    public void testConstructor() {
        assertNotNull(parser);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testParseNullSQL() {
        parser.parse(null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testParseEmptySQL() {
        parser.parse("");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testParseWhitespaceSQL() {
        parser.parse("   ");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidSQL() {
        parser.parse("INVALID SQL STATEMENT");
    }
    
    // ========== SELECT Statement Tests ==========
    
    @Test
    public void testParseSimpleSelect() {
        String sql = "SELECT * FROM users WHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertNotNull(stmt);
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertEquals("id = 1", stmt.getQueryConditions());
        assertEquals(sql, stmt.getRawSql());
    }
    
    @Test
    public void testParseSelectWithAlias() {
        String sql = "SELECT u.name FROM users u WHERE u.id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableAliases().size());
        assertEquals("users", stmt.getTableAliases().get("u"));
    }
    
    @Test
    public void testParseSelectWithJoin() {
        String sql = "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id WHERE u.id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(2, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertTrue(stmt.getTableNames().contains("orders"));
        assertEquals(2, stmt.getTableAliases().size());
        assertEquals("users", stmt.getTableAliases().get("u"));
        assertEquals("orders", stmt.getTableAliases().get("o"));
        assertEquals("u.id = 1", stmt.getQueryConditions());
    }
    
    @Test
    public void testParseSelectWithMultipleJoins() {
        String sql = "SELECT * FROM users u " +
                     "JOIN orders o ON u.id = o.user_id " +
                     "JOIN products p ON o.product_id = p.id " +
                     "WHERE u.id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(3, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertTrue(stmt.getTableNames().contains("orders"));
        assertTrue(stmt.getTableNames().contains("products"));
    }
    
    @Test
    public void testParseSelectWithLeftJoin() {
        String sql = "SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals(2, stmt.getTableNames().size());
    }
    
    @Test
    public void testParseSelectWithComplexWhere() {
        String sql = "SELECT * FROM users WHERE id > 10 AND name LIKE 'John%' OR age BETWEEN 20 AND 30";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertNotNull(stmt.getQueryConditions());
        assertTrue(stmt.getQueryConditions().contains("id > 10"));
    }
    
    @Test
    public void testParseSelectWithoutWhere() {
        String sql = "SELECT * FROM users";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertNull(stmt.getQueryConditions());
    }
    
    // ========== UPDATE Statement Tests ==========
    
    @Test
    public void testParseSimpleUpdate() {
        String sql = "UPDATE users SET name = 'John' WHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertNotNull(stmt);
        assertEquals(SQLStatement.StatementType.UPDATE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertEquals("id = 1", stmt.getQueryConditions());
        assertEquals(sql, stmt.getRawSql());
    }
    
    @Test
    public void testParseUpdateWithAlias() {
        String sql = "UPDATE users u SET u.name = 'John' WHERE u.id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.UPDATE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableAliases().size());
        assertEquals("users", stmt.getTableAliases().get("u"));
    }
    
    @Test
    public void testParseUpdateWithoutWhere() {
        String sql = "UPDATE users SET name = 'John'";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.UPDATE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertNull(stmt.getQueryConditions());
    }
    
    @Test
    public void testParseUpdateMultipleColumns() {
        String sql = "UPDATE users SET name = 'John', age = 30, city = 'NYC' WHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.UPDATE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals("id = 1", stmt.getQueryConditions());
    }
    
    // ========== INSERT Statement Tests ==========
    
    @Test
    public void testParseSimpleInsert() {
        String sql = "INSERT INTO users (name, age) VALUES ('John', 30)";
        SQLStatement stmt = parser.parse(sql);
        
        assertNotNull(stmt);
        assertEquals(SQLStatement.StatementType.INSERT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertEquals("INSERT", stmt.getQueryConditions());
        assertEquals(sql, stmt.getRawSql());
    }
    
    @Test
    public void testParseInsertWithoutColumns() {
        String sql = "INSERT INTO users VALUES ('John', 30, 'NYC')";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.INSERT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    @Test
    public void testParseInsertMultipleRows() {
        String sql = "INSERT INTO users (name, age) VALUES ('John', 30), ('Jane', 25)";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.INSERT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    // ========== DELETE Statement Tests ==========
    
    @Test
    public void testParseSimpleDelete() {
        String sql = "DELETE FROM users WHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertNotNull(stmt);
        assertEquals(SQLStatement.StatementType.DELETE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableNames().size());
        assertTrue(stmt.getTableNames().contains("users"));
        assertEquals("id = 1", stmt.getQueryConditions());
        assertEquals(sql, stmt.getRawSql());
    }
    
    @Test
    public void testParseDeleteWithAlias() {
        String sql = "DELETE FROM users u WHERE u.id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.DELETE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertEquals(1, stmt.getTableAliases().size());
        assertEquals("users", stmt.getTableAliases().get("u"));
    }
    
    @Test
    public void testParseDeleteWithoutWhere() {
        String sql = "DELETE FROM users";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.DELETE, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertNull(stmt.getQueryConditions());
    }
    
    @Test
    public void testParseDeleteWithComplexWhere() {
        String sql = "DELETE FROM users WHERE id > 100 AND created_at < '2020-01-01'";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.DELETE, stmt.getStatementType());
        assertNotNull(stmt.getQueryConditions());
    }
    
    // ========== Column Extraction Tests ==========
    
    @Test
    public void testExtractColumnsFromWhereSimple() {
        List<String> columns = parser.extractColumnsFromWhere("id = 1");
        
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
    }
    
    @Test
    public void testExtractColumnsFromWhereMultiple() {
        List<String> columns = parser.extractColumnsFromWhere("id = 1 AND name = 'John' AND age > 20");
        
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("name"));
        assertTrue(columns.contains("age"));
    }
    
    @Test
    public void testExtractColumnsFromWhereWithOperators() {
        List<String> columns = parser.extractColumnsFromWhere("id > 10 AND age < 50 AND name LIKE 'J%'");
        
        assertNotNull(columns);
        assertTrue(columns.contains("id"));
        assertTrue(columns.contains("age"));
        assertTrue(columns.contains("name"));
    }
    
    @Test
    public void testExtractColumnsFromWhereNull() {
        List<String> columns = parser.extractColumnsFromWhere(null);
        
        assertNotNull(columns);
        assertTrue(columns.isEmpty());
    }
    
    @Test
    public void testExtractColumnsFromWhereEmpty() {
        List<String> columns = parser.extractColumnsFromWhere("");
        
        assertNotNull(columns);
        assertTrue(columns.isEmpty());
    }
    
    // ========== Edge Cases ==========
    
    @Test
    public void testParseCaseInsensitive() {
        String sql = "select * from users where id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    @Test
    public void testParseWithExtraWhitespace() {
        String sql = "SELECT   *   FROM   users   WHERE   id   =   1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    @Test
    public void testParseWithNewlines() {
        String sql = "SELECT *\nFROM users\nWHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    @Test
    public void testParseSelectWithInnerJoin() {
        String sql = "SELECT * FROM users u INNER JOIN orders o ON u.id = o.user_id";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals(2, stmt.getTableNames().size());
    }
    
    @Test
    public void testParseSelectWithRightJoin() {
        String sql = "SELECT * FROM users u RIGHT JOIN orders o ON u.id = o.user_id";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals(2, stmt.getTableNames().size());
    }
    
    @Test
    public void testParseTableNameWithSchema() {
        String sql = "SELECT * FROM myschema.users WHERE id = 1";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        // JSQLParser may include schema in table name
        assertNotNull(stmt.getTableName());
    }
    
    @Test
    public void testParseSelectWithOrderBy() {
        String sql = "SELECT * FROM users WHERE id > 10 ORDER BY name ASC";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
        assertNotNull(stmt.getQueryConditions());
    }
    
    @Test
    public void testParseSelectWithLimit() {
        String sql = "SELECT * FROM users WHERE id > 10 LIMIT 10";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
    
    @Test
    public void testParseSelectWithGroupBy() {
        String sql = "SELECT COUNT(*), city FROM users GROUP BY city";
        SQLStatement stmt = parser.parse(sql);
        
        assertEquals(SQLStatement.StatementType.SELECT, stmt.getStatementType());
        assertEquals("users", stmt.getTableName());
    }
}
