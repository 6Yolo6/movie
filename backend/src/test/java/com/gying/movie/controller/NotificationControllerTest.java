package com.gying.movie.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.UserNotification;
import com.gying.movie.mapper.UserNotificationMapper;
import com.gying.movie.service.impl.UserNotificationServiceImpl;
import com.gying.movie.utils.AuthHelper;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {
    private final UserNotificationMapper mapper = mock(UserNotificationMapper.class);
    private final UserNotificationServiceImpl service = new UserNotificationServiceImpl();
    private final AuthHelper auth = mock(AuthHelper.class);
    private final NotificationController controller = new NotificationController(service, auth);
    private final MybatisConfiguration configuration = new MybatisConfiguration();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(auth.requireUser("Bearer fixture")).thenReturn(new AuthUser(7L, "fixture", "USER"));
        // SQL generation only; never open a connection or load production configuration.
        configuration.addMapper(UserNotificationMapper.class);
    }

    @ParameterizedTest
    @CsvSource({"7,0", "29,3", "7,1000000"})
    void markAllReadUsesOneUserScopedUnreadUpdateAndReturnsItsAffectedRows(long userId, int changed) {
        when(auth.requireUser("Bearer fixture")).thenReturn(new AuthUser(userId, "fixture", "USER"));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(changed);

        assertEquals(Map.of("updated", changed), controller.markAllRead("Bearer fixture"));

        ArgumentCaptor<Wrapper<UserNotification>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), update.capture());
        BoundSql bound = boundSql("update", update.getValue());
        // Notifications have no deleted_at field; retain the existing read_flag=false (not NULL) scope.
        assertEquals("UPDATE user_notification SET read_flag=? WHERE (user_id = ? AND read_flag = ?)", normalized(bound));
        assertEquals(List.of(true, userId, false), values(bound));
        // No full SELECT, entity materialization or per-row update is permitted.
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingUserIdCannotBecomeAnUnscopedUpdate() {
        when(auth.requireUser("Bearer fixture")).thenReturn(new AuthUser(null, "fixture", "USER"));
        assertEquals(Map.of("updated", 0), controller.markAllRead("Bearer fixture"));
        verifyNoInteractions(mapper);
    }

    @Test
    void unauthenticatedMarkAllReadNeverTouchesTheDatabase() {
        when(auth.requireUser(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var error = assertThrows(ResponseStatusException.class, () -> controller.markAllRead(null));
        assertEquals(401, error.getStatusCode().value());
        verifyNoInteractions(mapper);
    }

    @Test
    void updateFailureIsNotReportedAsASuccessfulMutation() {
        var failure = new DataAccessResourceFailureException("fixture update outage");
        when(mapper.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> controller.markAllRead("Bearer fixture")));
    }

    @ParameterizedTest
    @CsvSource({"-2147483648,-2147483648,1,1", "0,0,1,1", "2,20,2,20", "2147483647,2147483647,2147483647,100"})
    void notificationListKeepsTheUserUnreadFilterAndExistingPageBounds(int page, int size, long expectedPage, long expectedSize) {
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = controller.list(page, size, true, "Bearer fixture");

        assertEquals(expectedPage, result.getCurrent());
        assertEquals(expectedSize, result.getSize());
        ArgumentCaptor<Wrapper<UserNotification>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), query.capture());
        BoundSql bound = boundSql("selectList", query.getValue());
        assertTrue(normalized(bound).endsWith("WHERE (user_id = ? AND read_flag = ?) ORDER BY created_at DESC"));
        assertEquals(List.of(7L, false), values(bound));
        verifyNoMoreInteractions(mapper);
    }

    private BoundSql boundSql(String method, Wrapper<UserNotification> wrapper) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("et", null);
        parameters.put("ew", wrapper);
        return configuration.getMappedStatement(UserNotificationMapper.class.getName() + "." + method)
                .getBoundSql(parameters);
    }

    private List<Object> values(BoundSql bound) {
        return bound.getParameterMappings().stream()
                .map(parameter -> configuration.newMetaObject(bound.getParameterObject()).getValue(parameter.getProperty()))
                .toList();
    }

    private String normalized(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }
}
