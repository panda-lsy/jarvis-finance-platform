package com.jarvis.research.service;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseHealthServiceTest {

    @Test
    void reportsUpWhenSelectOneSucceeds() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        DatabaseHealthService.CheckResult result = new DatabaseHealthService(dataSource).check();

        assertTrue(result.up());
        assertEquals("PostgreSQL", result.product());
        assertNotNull(result.latencyMs());
        verify(statement).setQueryTimeout(2);
    }

    @Test
    void reportsDownWithoutLeakingExceptionWhenConnectionFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("password=secret"));

        DatabaseHealthService.CheckResult result = new DatabaseHealthService(dataSource).check();

        assertFalse(result.up());
        assertNull(result.product());
        assertEquals("down", result.publicView().get("status"));
        assertFalse(result.publicView().containsValue("password=secret"));
    }
}
