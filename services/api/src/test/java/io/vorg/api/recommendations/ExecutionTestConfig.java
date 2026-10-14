package io.vorg.api.recommendations;

import io.vorg.api.integration.core.AdapterCommandPort;
import io.vorg.api.integration.simulated.SimulatedTmsAdapter;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.events.DataMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * <b>Test-only</b> seams for the execution ITs, on top of the real stack (OR-Tools generator, simulated adapters):
 * <ul>
 *   <li>{@link Faults}: wraps the SIM-TMS command port so a test can make one call refuse (the compensation path) or
 *       make the source acknowledge a command without ever applying it (the verification timeout);</li>
 *   <li>{@link BumpablePolicyVersions}: lets a test raise a policy's current version (the policy-version stale path).</li>
 * </ul>
 * Both are inert until a test arms them, and the tests reset them after each test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExecutionTestConfig {

    /** What the wrapped SIM-TMS port does with the next calls. */
    public static final class Faults {

        private volatile int failOnCall;
        private volatile boolean drop;
        private final AtomicInteger calls = new AtomicInteger();

        /** The {@code n}-th call from now (1-based) is refused; every other call goes through. */
        public void failOnCall(int n) {
            calls.set(0);
            drop = false;
            failOnCall = n;
        }

        /** Every call is acknowledged as applied but never reaches the source. */
        public void dropAll() {
            failOnCall = 0;
            drop = true;
        }

        public void reset() {
            failOnCall = 0;
            drop = false;
            calls.set(0);
        }
    }

    public static final Faults FAULTS = new Faults();

    /** Wraps a SIM-TMS port with {@link #FAULTS}. */
    static final class FaultInjectingPort implements AdapterCommandPort {

        private final AdapterCommandPort delegate;

        FaultInjectingPort(AdapterCommandPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public String sourceSystem() {
            return delegate.sourceSystem();
        }

        @Override
        public DataMode dataMode() {
            return delegate.dataMode();
        }

        @Override
        public Set<String> commandTypes() {
            return delegate.commandTypes();
        }

        @Override
        public Result apply(UUID tenantId, UUID siteId, Command command) {
            if (FAULTS.drop) {
                Object target = command.params().get("trip_code");
                Map<String, Object> undo = new LinkedHashMap<>(command.params());
                return new Result(command.commandType(), String.valueOf(target), null,
                        String.valueOf(command.params().get("to_dock")), 0, new Command(command.commandType(), undo));
            }
            int n = FAULTS.calls.incrementAndGet();
            if (FAULTS.failOnCall > 0 && n == FAULTS.failOnCall) {
                throw new CommandRefusedException("injected_fault", "Test fault on call " + n);
            }
            return delegate.apply(tenantId, siteId, command);
        }
    }

    @Bean
    static BeanPostProcessor faultInjectingTmsPort() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AdapterCommandPort port && !(bean instanceof FaultInjectingPort)
                        && SimulatedTmsAdapter.SOURCE_SYSTEM.equals(port.sourceSystem())) {
                    return new FaultInjectingPort(port);
                }
                return bean;
            }
        };
    }

    /** {@link PolicyVersions} whose current versions a test can raise. */
    public static class BumpablePolicyVersions extends PolicyVersions {

        private final Map<String, Integer> bumps = new ConcurrentHashMap<>();

        public BumpablePolicyVersions(PolicyService policy, DockReassignmentPolicy dockReassignment) {
            super(policy, dockReassignment);
        }

        public void bump(String policy) {
            bumps.merge(policy, 1, Integer::sum);
        }

        public void reset() {
            bumps.clear();
        }

        @Override
        public Map<String, Integer> current() {
            Map<String, Integer> versions = new LinkedHashMap<>(super.current());
            bumps.forEach((name, by) -> versions.computeIfPresent(name, (k, v) -> v + by));
            return versions;
        }
    }

    @Bean
    @Primary
    BumpablePolicyVersions bumpablePolicyVersions(PolicyService policy, DockReassignmentPolicy dockReassignment) {
        return new BumpablePolicyVersions(policy, dockReassignment);
    }
}
