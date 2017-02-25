package org.openhab.binding.modbus.internal;

import java.util.Optional;

import org.openhab.binding.modbus.internal.pooling.EndpointPoolConfiguration;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;

public interface ModbusManager {

    public interface PollTask {
        // metadata, e.g. last polled?
        ModbusSlaveEndpoint getEndpoint();
    }

    /**
     * Configure general connection settings with a given endpoint
     *
     * @param endpoint
     * @param configuration
     */
    public void setEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint, EndpointPoolConfiguration configuration);

    public Optional<EndpointPoolConfiguration> getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint);

    /**
     *
     * @param endpoint
     * @param message
     * @param config
     * @param pollPeriodMillis
     * @return string identifier for the poll
     */
    public PollTask registerRegularPoll(ModbusSlaveEndpoint endpoint, ModbusRequestBlueprint message,
            long pollPeriodMillis, ModbusSlaveReader callback);

    public void executeOneTimePoll(ModbusSlaveEndpoint endpoint, ModbusRequestBlueprint message,
            ModbusSlaveReader callback);

    public boolean unregisterRegularPoll(PollTask task);

}
