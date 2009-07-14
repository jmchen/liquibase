package liquibase.statementexecute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.JdbcConnection;
import liquibase.database.example.ExampleCustomDatabase;
import liquibase.database.core.MockDatabase;
import liquibase.database.core.UnsupportedDatabase;
import liquibase.executor.ExecutorService;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.test.TestContext;

import org.junit.After;

public abstract class AbstractExecuteTest {

    private Set<Class<? extends Database>> testedDatabases = new HashSet<Class<? extends Database>>();
    protected SqlStatement statementUnderTest;

    @After
    public void reset() {
        testedDatabases = new HashSet<Class<? extends Database>>();
        this.statementUnderTest = null;
    }

    protected abstract List<? extends SqlStatement> setupStatements(Database database);

    protected void testOnAll(String expectedSql) throws Exception {
        test(expectedSql, null, null);
    }

    protected void assertCorrectOnRest(String expectedSql) throws Exception {
        assertCorrect(expectedSql);
    }

    protected void assertCorrect(String expectedSql, Class<? extends Database>... includeDatabases) throws Exception {
        assertCorrect(new String[] {expectedSql}, includeDatabases);
    }

    protected void assertCorrect(String[] expectedSql, Class<? extends Database>... includeDatabases) throws Exception {
        assertNotNull(statementUnderTest);
        
        test(expectedSql, includeDatabases, null);
    }

    public void testOnAllExcept(String expectedSql, Class<? extends Database>... excludedDatabases) throws Exception {
        test(expectedSql, null, excludedDatabases);
    }

    private void test(String expectedSql, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) throws Exception {
        test(new String[] {expectedSql}, includeDatabases, excludeDatabases);
    }

    private void test(String[] expectedSql, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) throws Exception {

        if (expectedSql != null) {
            for (Database database : TestContext.getInstance().getAllDatabases()) {
                if (shouldTestDatabase(database, includeDatabases, excludeDatabases)) {
                    testedDatabases.add(database.getClass());

                    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statementUnderTest, database);

                    assertNotNull("Null SQL for " + database, sql);
                    assertEquals("Unexpected number of  SQL statements for " + database, expectedSql.length, sql.length);

                    int index = 0;
                    for (String convertedSql : expectedSql) {
                        convertedSql = replaceEscaping(convertedSql, database);
                        convertedSql = replaceDatabaseClauses(convertedSql, database);
                        convertedSql = replaceStandardTypes(convertedSql, database);

                        assertEquals("Incorrect SQL for " + database.getClass().getName(), convertedSql.toLowerCase(), sql[index].toSql().toLowerCase());
                        index++;
                    }
                }
            }
        }

        resetAvailableDatabases();
        for (Database availableDatabase : TestContext.getInstance().getAvailableDatabases()) {
            Statement statement = ((JdbcConnection) availableDatabase.getConnection()).getUnderlyingConnection().createStatement();
            if (shouldTestDatabase(availableDatabase, includeDatabases, excludeDatabases)) {
                String sqlToRun = SqlGeneratorFactory.getInstance().generateSql(statementUnderTest, availableDatabase)[0].toSql();
                try {
                    statement.execute(sqlToRun);
                } catch (Exception e) {
                    System.out.println("Failed to execute against " + availableDatabase.getTypeName() + ": " + sqlToRun);
                    throw e;

                }
            }
        }
    }

    private String replaceStandardTypes(String convertedSql, Database database) {
        convertedSql = replaceType("int", convertedSql, database);
        convertedSql = replaceType("datetime", convertedSql, database);
        convertedSql = replaceType("boolean", convertedSql, database);

        convertedSql = convertedSql.replaceAll("FALSE", database.getFalseBooleanValue());
        convertedSql = convertedSql.replaceAll("TRUE", database.getFalseBooleanValue());

        return convertedSql;
    }

    private String replaceType(String type, String baseString, Database database) {
        return baseString.replaceAll(" " + type + " ", " " + database.getColumnType(type, false) + " ")
                .replaceAll(" " + type + ",", " " + database.getColumnType(type, false) + ",");
    }

    private String replaceDatabaseClauses(String convertedSql, Database database) {
        return convertedSql.replaceFirst("auto_increment_clause", database.getAutoIncrementClause());
    }

    private boolean shouldTestDatabase(Database database, Class<? extends Database>[] includeDatabases, Class<? extends Database>[] excludeDatabases) {
        if (database instanceof MockDatabase || database instanceof ExampleCustomDatabase || database instanceof UnsupportedDatabase) {
            return false;
        }
        if (!SqlGeneratorFactory.getInstance().supports(statementUnderTest, database)
                || SqlGeneratorFactory.getInstance().validate(statementUnderTest, database).hasErrors()) {
            return false;
        }

        boolean shouldInclude = true;
        if (includeDatabases != null && includeDatabases.length > 0) {
            shouldInclude = Arrays.asList(includeDatabases).contains(database.getClass());
        }

        boolean shouldExclude = false;
        if (excludeDatabases != null && excludeDatabases.length > 0) {
            shouldExclude = Arrays.asList(excludeDatabases).contains(database.getClass());
        }

        return !shouldExclude && shouldInclude && !testedDatabases.contains(database.getClass());


    }

    private String replaceEscaping(String expectedSql, Database database) {
        String convertedSql = expectedSql;
        int lastIndex = 0;
        while ((lastIndex = convertedSql.indexOf("[", lastIndex)) >= 0) {
            String objectName = convertedSql.substring(lastIndex + 1, convertedSql.indexOf("]", lastIndex));
            convertedSql = convertedSql.replace("[" + objectName + "]", database.escapeDatabaseObject(objectName));
            lastIndex++;
        }

        return convertedSql;
    }

    public void resetAvailableDatabases() throws Exception {
        for (Database database : TestContext.getInstance().getAvailableDatabases()) {
            DatabaseConnection connection = database.getConnection();
            Statement connectionStatement = ((JdbcConnection) connection).getUnderlyingConnection().createStatement();

            if (database.supportsSchemas()) {
                database.dropDatabaseObjects(TestContext.ALT_SCHEMA);
                connection.commit();
                if (!database.isPeculiarLiquibaseSchema()) {
                    try {
                        connectionStatement.executeUpdate("drop table " + database.escapeTableName(TestContext.ALT_SCHEMA, database.getDatabaseChangeLogLockTableName()));
                    } catch (SQLException e) {
                        ;
                    }
                    connection.commit();
                }
                if (!database.isPeculiarLiquibaseSchema()) {
                    try {
                        connectionStatement.executeUpdate("drop table " + database.escapeTableName(TestContext.ALT_SCHEMA, database.getDatabaseChangeLogTableName()));
                    } catch (SQLException e) {
                        ;
                    }
                    connection.commit();
                }
            }
            database.dropDatabaseObjects(database.convertRequestedSchemaToSchema(null));
            try {
                connectionStatement.executeUpdate("drop table " + database.escapeTableName(database.getLiquibaseSchemaName(), database.getDatabaseChangeLogLockTableName()));
            } catch (SQLException e) {
                ;
            }
            connection.commit();
            try {
                connectionStatement.executeUpdate("drop table " + database.escapeTableName(database.getLiquibaseSchemaName(), database.getDatabaseChangeLogTableName()));
            } catch (SQLException e) {
                ;
            }
            connection.commit();

            List<? extends SqlStatement> setupStatements = setupStatements(database);
            if (setupStatements != null) {
                for (SqlStatement statement : setupStatements) {
                    ExecutorService.getInstance().getWriteExecutor(database).execute(statement);
                }
            }
            connectionStatement.close();
        }
    }

}