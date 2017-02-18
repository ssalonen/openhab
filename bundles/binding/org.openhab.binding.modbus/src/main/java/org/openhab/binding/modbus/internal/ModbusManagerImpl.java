package org.openhab.binding.modbus.internal;

import java.util.Optional;

import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.SwallowedExceptionListener;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.openhab.binding.modbus.internal.pooling.EndpointPoolConfiguration;
import org.openhab.binding.modbus.internal.pooling.ModbusSerialSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveConnectionFactoryImpl;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusSlaveEndpointVisitor;
import org.openhab.binding.modbus.internal.pooling.ModbusTCPSlaveEndpoint;
import org.openhab.binding.modbus.internal.pooling.ModbusUDPSlaveEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.io.ModbusSerialTransaction;
import net.wimpi.modbus.io.ModbusTCPTransaction;
import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.io.ModbusUDPTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.msg.ReadCoilsRequest;
import net.wimpi.modbus.msg.ReadInputDiscretesRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.net.ModbusSlaveConnection;

public class ModbusManagerImpl implements ModbusManager {

    private static final Logger logger = LoggerFactory.getLogger(ModbusManagerImpl.class);
    private static GenericKeyedObjectPoolConfig poolConfig = new GenericKeyedObjectPoolConfig();

    static {
        // When the pool is exhausted, multiple calling threads may be simultaneously blocked waiting for instances to
        // become available. As of pool 1.5, a "fairness" algorithm has been implemented to ensure that threads receive
        // available instances in request arrival order.
        poolConfig.setFairness(true);
        // Limit one connection per endpoint (i.e. same ip:port pair or same serial device).
        // If there are multiple read/write requests to process at the same time, block until previous one finishes
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxTotalPerKey(1);

        // block infinitely when exhausted
        poolConfig.setMaxWaitMillis(-1);

        // make sure we return connected connections from/to connection pool
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);

        // disable JMX
        poolConfig.setJmxEnabled(false);
    }
    //
    // /**
    // * For testing
    // */
    // static KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> getReconstructedConnectionPoolForTesting() {
    // reconstructConnectionPool();
    // return connectionPool;
    // }

    /**
     * We use connection pool to ensure that only single transaction is ongoing per each endpoint. This is especially
     * important with serial slaves but practice has shown that even many tcp slaves have limited
     * capability to handle many connections at the same time
     *
     * Relevant discussion at the time of implementation:
     * - https://community.openhab.org/t/modbus-connection-problem/6108/
     * - https://community.openhab.org/t/connection-pooling-in-modbus-binding/5246/
     */
    private static KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool;
    private static ModbusSlaveConnectionFactoryImpl connectionFactory;

    // private static void reconstructConnectionPool() {
    static {
        connectionFactory = new ModbusSlaveConnectionFactoryImpl();
        GenericKeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> genericKeyedObjectPool = new GenericKeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection>(
                connectionFactory, poolConfig);
        genericKeyedObjectPool.setSwallowedExceptionListener(new SwallowedExceptionListener() {

            @Override
            public void onSwallowException(Exception e) {
                logger.error("Connection pool swallowed unexpected exception: {}", e.getMessage());

            }
        });
        connectionPool = genericKeyedObjectPool;
    }

    // /**
    // * Clear all configuration and close all connections
    // */
    // private void clearConnectionPool() {
    // try {
    // // Closes all connections by calling destroyObject method in the ObjectFactory implementation
    // if (connectionPool != null) {
    // connectionPool.close();
    // }
    // } catch (Exception e) {
    // // Should not happen
    // logger.error("Error clearing connections", e);
    // }
    // }

    @Override
    public void setEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint, EndpointPoolConfiguration configuration) {
        connectionFactory.getEndpointPoolConfigs().put(endpoint, configuration);
    }

    @Override
    public Optional<EndpointPoolConfiguration> getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint) {
        return Optional.ofNullable(connectionFactory.getEndpointPoolConfigs().get(endpoint));
    }

    @Override
    public void executeOneTimePoll(ModbusSlaveEndpoint endpoint, ModbusRequestBlueprint message,
            ModbusSlaveReader callback) {
        Optional<ModbusSlaveConnection> connection = borrowConnection(endpoint);

        try {
            if (!connection.isPresent()) {
                logger.warn("Not connected to endpoint {}-- aborting request {}", endpoint, message);
                callback.internalUpdateReadErrorItem("", new ModbusConnectionException(endpoint));
            }
            ModbusTransaction transaction = getTransactionForEndpoint(endpoint);
            ModbusRequest request = getRequest(message);
            transaction.setRequest(request);
            try {
                transaction.execute();
            } catch (ModbusException e) {
                // Note, one could catch ModbusIOException and ModbusSlaveException if more detailed
                // exception handling is required. For now, all exceptions are handled the same way with writes.
                logger.error("Error when executing read request ({}): {} {}", request, e.getClass().getName(),
                        e.getMessage());
                invalidate(endpoint, connection);
                // set connection to null such that it is not returned to pool
                connection = null;
                callback.internalUpdateReadErrorItem("", e);
            }
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}) {}", response.getFunctionCode(), response.getHexMessage());
            if ((response.getTransactionID() != transaction.getTransactionID()) && !response.isHeadless()) {
                logger.warn(
                        "Transaction id of the response does not match request {}.  Endpoint {}. Connection: {}. Ignoring response.",
                        request, endpoint, connection);
                callback.internalUpdateReadErrorItem("", new ModbusUnexpectedTransactionIdException());
            }
        } finally {
            returnConnection(endpoint, connection);
        }
    }

    private ModbusRequest getRequest(ModbusRequestBlueprint message) {
        ModbusRequest request;
        if (message.getFunctionCode() == Modbus.READ_COILS) {
            request = new ReadCoilsRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == Modbus.READ_INPUT_DISCRETES) {
            request = new ReadInputDiscretesRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == Modbus.READ_MULTIPLE_REGISTERS) {
            request = new ReadMultipleRegistersRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == Modbus.READ_INPUT_REGISTERS) {
            request = new ReadInputDiscretesRequest(message.getReference(), message.getDataLength());
        } else {
            throw new IllegalArgumentException(String.format("Unexpected function code %d", message.getFunctionCode()));
        }
        return request;
    }

    private ModbusTransaction getTransactionForEndpoint(ModbusSlaveEndpoint endpoint) {
        ModbusTransaction transaction = endpoint.accept(new ModbusSlaveEndpointVisitor<ModbusTransaction>() {

            @Override
            public ModbusTransaction visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey) {
                ModbusTCPTransaction transaction = new ModbusTCPTransaction();
                transaction.setReconnecting(false);
                return transaction;
            }

            @Override
            public ModbusTransaction visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                return new ModbusSerialTransaction();
            }

            @Override
            public ModbusTransaction visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey) {
                return new ModbusUDPTransaction();
            }
        });
        return transaction;
    }

    @Override
    public boolean unregisterRegularPoll(PollTask task) {
        // TODO: mark connection to be destroyed/closed if opened before current time
        return false;
    }

    private Optional<ModbusSlaveConnection> borrowConnection(ModbusSlaveEndpoint endpoint) {
        Optional<ModbusSlaveConnection> connection = Optional.empty();
        long start = System.currentTimeMillis();
        try {
            connection = Optional.ofNullable(connectionPool.borrowObject(endpoint));
        } catch (Exception e) {
            logger.warn("Error getting a new connection for endpoint {}. Error was: {}", endpoint, e.getMessage());
        }
        logger.trace("borrowing connection (got {}) for endpoint {} took {} ms", connection, endpoint,
                System.currentTimeMillis() - start);
        return connection;
    }

    private void invalidate(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        if (!connection.isPresent()) {
            return;
        }
        connection.ifPresent(con -> {
            try {
                connectionPool.invalidateObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error invalidating connection in pool for endpoint {}. Error was: {}", endpoint,
                        e.getMessage());
            }
        });
    }

    private void returnConnection(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        connection.ifPresent(con -> {
            try {
                connectionPool.returnObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error returning connection to pool for endpoint {}. Error was: {}", endpoint,
                        e.getMessage());
            }
        });
        logger.trace("returned connection for endpoint {}", endpoint);
    }

}
