package io.vorg.api.platform.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the runtime {@code vo_app} {@link DataSource}: on every connection checkout, binds the current
 * {@link TenantContext} (or the nil UUID when absent) as the session-local {@code app.tenant_id} setting that the
 * {@code tenant_isolation} RLS policies read (ADR-07). Missing tenant context therefore fails closed: every tenant
 * table reads as empty rather than leaking rows across tenants. {@code app.tenant_id} is reset when the connection
 * is returned to the pool so a reused physical connection never carries a stale tenant into its next checkout.
 */
public class TenantBindingDataSource extends DelegatingDataSource {

    /** Bound when no {@link TenantContext} is set — matches no row under {@code tenant_isolation} (fail closed). */
    public static final UUID NIL_TENANT = new UUID(0L, 0L);

    public TenantBindingDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        UUID tenantId = TenantContext.current().orElse(NIL_TENANT);
        try (PreparedStatement statement = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
        return proxy(connection);
    }

    private static Connection proxy(Connection target) {
        InvocationHandler handler = (proxyObj, method, args) -> {
            if ("close".equals(method.getName())) {
                resetTenantSetting(target);
                return invoke(target, method, args);
            }
            return invoke(target, method, args);
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private static void resetTenantSetting(Connection target) {
        try {
            if (!target.isClosed()) {
                try (PreparedStatement statement = target.prepareStatement("RESET app.tenant_id")) {
                    statement.execute();
                }
            }
        } catch (SQLException ignored) {
            // The connection is already broken; closing it below is what matters.
        }
    }

    private static Object invoke(Connection target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
