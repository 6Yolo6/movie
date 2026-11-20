package com.gying.movie.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.service.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class TmdbGyingResourceFollowUpServiceTest {
    private TmdbGyingResourceFollowUpService helper(DataSource source) {
        return new TmdbGyingResourceFollowUpService(new ResourceHubProperties(), mock(IResourceHubTaskService.class),
                mock(IMovieMetadataService.class), mock(IMovieSourceIdentityService.class), mock(GyingSourceWorkflowService.class),
                mock(P2pArchiveTaskService.class), new ObjectMapper(), new JdbcTemplate(source), mock(TransactionTemplate.class), mock(IResourceHubConfigService.class));
    }
    private Connection connection(DataSource source, int acquire, int release) throws Exception {
        Connection connection = mock(Connection.class); when(source.getConnection()).thenReturn(connection);
        PreparedStatement lock = mock(PreparedStatement.class), unlock = mock(PreparedStatement.class);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 10)")).thenReturn(lock);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlock);
        ResultSet lockResult = mock(ResultSet.class), releaseResult = mock(ResultSet.class);
        when(lock.executeQuery()).thenReturn(lockResult); when(unlock.executeQuery()).thenReturn(releaseResult);
        when(lockResult.next()).thenReturn(true); when(lockResult.getInt(1)).thenReturn(acquire);
        when(releaseResult.next()).thenReturn(true); when(releaseResult.getInt(1)).thenReturn(release);
        return connection;
    }

    @Test void databaseLockIsHeldThroughoutActionAndReleasedAfterAcknowledgement() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = connection(source, 1, 1);
        assertEquals("ACK", helper(source).withDatabaseLock("producer", () -> {
            try { verify(connection, never()).prepareStatement("SELECT RELEASE_LOCK(?)"); }
            catch (Exception error) { throw new AssertionError(error); }
            return "ACK";
        }));
        verify(connection).prepareStatement("SELECT RELEASE_LOCK(?)"); verify(connection).close(); verify(connection, never()).abort(any());
    }

    @Test void unsuccessfulAcquireDoesNoActionOrTransaction() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = connection(source, 0, 1);
        assertEquals("GYING_RESOURCE_DATABASE_LOCK_BUSY", assertThrows(IllegalStateException.class,
                () -> helper(source).withDatabaseLock("producer", () -> { fail("No action without database lock"); return null; })).getMessage());
        verify(connection, never()).prepareStatement("SELECT RELEASE_LOCK(?)"); verify(connection).close();
    }

    @Test void releaseFailureRetiresPhysicalSessionWithoutTurningCommittedAckIntoRetry() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = connection(source, 1, 0);
        assertEquals("COMMITTED_ACK", helper(source).withDatabaseLock("producer", () -> "COMMITTED_ACK"));
        verify(connection).abort(any()); verify(connection).close();
    }

    @Test void failedReleaseEvictsHikariPhysicalConnectionAndPreservesAck() throws Exception {
        com.zaxxer.hikari.HikariDataSource source = mock(com.zaxxer.hikari.HikariDataSource.class);
        Connection connection = connection(source, 1, 0);
        assertEquals("ACK", helper(source).withDatabaseLock("producer", () -> "ACK"));
        verify(source).evictConnection(connection); verify(connection).close();
    }

    @Test void nestedTransactionStopsBeforeAcquiringAnotherConnection() throws Exception {
        DataSource source = mock(DataSource.class); TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals("GYING_RESOURCE_NESTED_TRANSACTION_FORBIDDEN", assertThrows(IllegalStateException.class,
                    () -> helper(source).withDatabaseLock("producer", () -> "unreachable")).getMessage());
            verify(source, never()).getConnection();
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
