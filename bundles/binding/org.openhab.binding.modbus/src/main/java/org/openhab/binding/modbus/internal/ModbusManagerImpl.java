package org.openhab.binding.modbus.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.istack.internal.NotNull;

import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.io.ModbusSerialTransaction;
import net.wimpi.modbus.io.ModbusTCPTransaction;
import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.io.ModbusUDPTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.msg.ReadCoilsRequest;
import net.wimpi.modbus.msg.ReadCoilsResponse;
import net.wimpi.modbus.msg.ReadInputDiscretesRequest;
import net.wimpi.modbus.msg.ReadInputDiscretesResponse;
import net.wimpi.modbus.msg.ReadInputRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.msg.WriteCoilRequest;
import net.wimpi.modbus.msg.WriteMultipleRegistersRequest;
import net.wimpi.modbus.msg.WriteSingleRegisterRequest;
import net.wimpi.modbus.net.ModbusSlaveConnection;
import net.wimpi.modbus.procimg.Register;
import net.wimpi.modbus.util.BitVector;

public class ModbusManagerImpl implements ModbusManager {

    private static class PollTaskImpl implements PollTask {

        private ModbusSlaveEndpoint endpoint;

        public PollTaskImpl(ModbusSlaveEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public ModbusSlaveEndpoint getEndpoint() {
            return endpoint;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            PollTaskImpl other = (PollTaskImpl) obj;
            return this.endpoint.equals(other.getEndpoint());
        }

        @Override
        public int hashCode() {
            return this.endpoint.hashCode();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ModbusManagerImpl.class);
    private static GenericKeyedObjectPoolConfig generalPoolConfig = new GenericKeyedObjectPoolConfig();

    static {
        // When the pool is exhausted, multiple calling threads may be simultaneously blocked waiting for instances to
        // become available. As of pool 1.5, a "fairness" algorithm has been implemented to ensure that threads receive
        // available instances in request arrival order.
        generalPoolConfig.setFairness(true);
        // Limit one connection per endpoint (i.e. same ip:port pair or same serial device).
        // If there are multiple read/write requests to process at the same time, block until previous one finishes
        generalPoolConfig.setBlockWhenExhausted(true);
        generalPoolConfig.setMaxTotalPerKey(1);

        // block infinitely when exhausted
        generalPoolConfig.setMaxWaitMillis(-1);

        // make sure we return connected connections from/to connection pool
        generalPoolConfig.setTestOnBorrow(true);
        generalPoolConfig.setTestOnReturn(true);

        // disable JMX
        generalPoolConfig.setJmxEnabled(false);
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

    private volatile Map<PollTask, ScheduledFuture<?>> scheduledPollTasks = new ConcurrentHashMap<>();
    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(10);

    static {
        connectionFactory = new ModbusSlaveConnectionFactoryImpl();
        connectionFactory.setDefaultPoolConfigurationFactory(endpoint -> {
            return endpoint.accept(new ModbusSlaveEndpointVisitor<EndpointPoolConfiguration>() {

                @Override
                public EndpointPoolConfiguration visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    endpointPoolConfig
                            .setPassivateBorrowMinMillis(ModbusBinding.DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    // never "disconnect" (close/open serial port) serial connection between borrows
                    endpointPoolConfig.setReconnectAfterMillis(-1);
                    endpointPoolConfig
                            .setPassivateBorrowMinMillis(ModbusBinding.DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    endpointPoolConfig
                            .setPassivateBorrowMinMillis(ModbusBinding.DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    return endpointPoolConfig;
                }
            });
        });

        GenericKeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> genericKeyedObjectPool = new GenericKeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection>(
                connectionFactory, generalPoolConfig);
        genericKeyedObjectPool.setSwallowedExceptionListener(new SwallowedExceptionListener() {

            @Override
            public void onSwallowException(Exception e) {
                logger.error("Connection pool swallowed unexpected exception: {}", e.getMessage());

            }
        });
        connectionPool = genericKeyedObjectPool;
    }

    private void invokeCallbackWithResponse(ModbusReadRequestBlueprint message, ReadCallback callback,
            ModbusResponse response) {
        callback.accept(new ModbusSlaveReaderVisitor() {

            private State[] booleanToBooleanLikeStateCandidates(boolean boolValue) {
                State[] stateCandidatesBeforeTransformation;
                stateCandidatesBeforeTransformation = new State[] { boolValue ? OnOffType.ON : OnOffType.OFF,
                        boolValue ? OpenClosedType.OPEN : OpenClosedType.CLOSED };
                return stateCandidatesBeforeTransformation;
            }

            @Override
            public void visit(RawModbusSlaveReader reader) {
                if (message.getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
                    reader.internalUpdateItem(message, ((ReadCoilsResponse) response).getCoils());
                } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
                    reader.internalUpdateItem(message, ((ReadInputDiscretesResponse) response).getDiscretes());
                } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                    reader.internalUpdateItem(message, ((ReadMultipleRegistersResponse) response).getRegisters());
                } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                    reader.internalUpdateItem(message, ((ReadMultipleRegistersResponse) response).getRegisters());
                } else {
                    throw new IllegalArgumentException(
                            String.format("Unexpected function code %s", message.getFunctionCode()));
                }
            }

            private void updateFromBits(ModbusSlaveReaderUsingIOConnection reader, ItemIOConnection connection,
                    BitVector bits) {
                boolean booleanState = bits.getBit(connection.getIndex());
                State[] stateCandidatesForTransformation;
                if (connection.supportBooleanLikeState()) {
                    stateCandidatesForTransformation = booleanToBooleanLikeStateCandidates(booleanState);
                } else {
                    stateCandidatesForTransformation = new State[] {
                            booleanState ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO };
                }
                for (State newState : stateCandidatesForTransformation) {
                    boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
                    if (connection.supportsState(newState, stateChanged)) {
                        Transformation transformation = connection.getTransformation();
                        State transformedState = transformation == null ? newState
                                : transformation.transformState(connection.getAcceptedDataTypes(), newState);
                        if (transformedState != null) {
                            logger.trace("%s: Updating state {} (changed={}) matched ItemIOConnection {}.", message,
                                    newState, stateChanged, connection);
                            reader.internalUpdateItem(message, connection, transformedState);
                            connection.setPreviouslyPolledState(newState);
                        }
                    } else {
                        logger.trace(
                                "%s: Not updating since state {} (changed={}) not supported by ItemIOConnection {}.",
                                message, newState, stateChanged, connection);
                    }
                }
            }

            private void updateFromRegisters(ModbusSlaveReaderUsingIOConnection reader, ItemIOConnection connection,
                    Register[] registers) {
                String valueType = connection.getEffectiveValueType();
                State numericState = ModbusBinding.extractStateFromRegisters(registers, connection.getIndex(),
                        valueType);

                State[] stateCandidatesForTransformation;
                if (connection.supportBooleanLikeState()) {
                    boolean boolValue = !numericState.equals(DecimalType.ZERO);
                    stateCandidatesForTransformation = booleanToBooleanLikeStateCandidates(boolValue);
                } else {
                    stateCandidatesForTransformation = new State[] { numericState };
                }
                for (State newState : stateCandidatesForTransformation) {
                    boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
                    if (connection.supportsState(newState, stateChanged)) {
                        logger.trace("%s: Updating state {} (changed={}) matched ItemIOConnection {}.", message,
                                newState, stateChanged, connection);
                        Transformation transformation = connection.getTransformation();
                        State transformedState = transformation == null ? newState
                                : transformation.transformState(connection.getAcceptedDataTypes(), newState);
                        if (transformedState != null) {
                            reader.internalUpdateItem(message, connection, transformedState);
                            connection.setPreviouslyPolledState(newState);
                            break;
                        }
                    } else {
                        logger.trace(
                                "%s: Not updating since state {} (changed={}) not supported by ItemIOConnection {}.",
                                message, newState, stateChanged, connection);
                    }
                }
            }

            /*
             * Convert polled value (boolean bit with coil and discrete input, or numeric value with input register and
             * holding register) to "boolean state" if we have IOConnection bound to boolean states (ON/OFF, or
             * OPEN/CLOSED).
             *
             * Actually, we try both ON/OPEN and OFF/CLOSED, and go with the one that is accepted
             * by the transformation.
             *
             * Otherwise, go with the numeric value.
             */
            @Override
            public void visit(ModbusSlaveReaderUsingIOConnection reader) {
                List<ItemIOConnection> connections = reader.getItemIOConnections();
                for (ItemIOConnection connection : connections) {
                    if (message.getFunctionCode() == ModbusReadFunctionCode.READ_COILS
                            || message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
                        BitVector bits;
                        if (message.getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
                            bits = ((ReadCoilsResponse) response).getCoils();
                        } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
                            bits = ((ReadInputDiscretesResponse) response).getDiscretes();
                        } else {
                            throw new IllegalStateException();
                        }

                        if (connection.getIndex() >= message.getDataLength()) {
                            logger.warn(
                                    "IO connection {} read index '{}' is out-of-bound. Polled data length is only {} bits."
                                            + " Check your configuration!",
                                    connection, connection.getIndex(), message.getDataLength());
                            continue;
                        }
                        updateFromBits(reader, connection, bits);
                    } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_REGISTERS
                            || message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                        Register[] registers;
                        if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                            registers = ((ReadMultipleRegistersResponse) response).getRegisters();
                        } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                            registers = ((ReadMultipleRegistersResponse) response).getRegisters();
                        } else {
                            throw new IllegalStateException();
                        }
                        updateFromRegisters(reader, connection, registers);
                    } else {
                        throw new IllegalArgumentException(
                                String.format("Unexpected function code %s", message.getFunctionCode()));
                    }

                }
            }
        });
    }

    private ModbusRequest createRequest(ModbusReadRequestBlueprint message) {
        ModbusRequest request;
        if (message.getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
            request = new ReadCoilsRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
            request = new ReadInputDiscretesRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
            request = new ReadMultipleRegistersRequest(message.getReference(), message.getDataLength());
        } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_REGISTERS) {
            request = new ReadInputRegistersRequest(message.getReference(), message.getDataLength());
        } else {
            throw new IllegalArgumentException(String.format("Unexpected function code %s", message.getFunctionCode()));
        }
        request.setUnitID(message.getUnitID());
        request.setProtocolID(message.getProtocolID());

        return request;
    }

    private ModbusRequest createRequest(ModbusWriteRequestBlueprint message) {
        ModbusRequest[] request = new ModbusRequest[1];
        if (message.getFunctionCode() == ModbusWriteFunctionCode.WRITE_COIL) {
            message.accept(new ModbusWriteRequestBlueprintVisitor() {

                @Override
                public void visit(ModbusWriteRegisterRequestBlueprint blueprint) {
                    throw new IllegalStateException();
                }

                @Override
                public void visit(ModbusWriteCoilRequestBlueprint blueprint) {
                    request[0] = new WriteCoilRequest(message.getReference(), blueprint.getCoil());
                }
            });

        } else if (message.getFunctionCode() == ModbusWriteFunctionCode.WRITE_MULTIPLE_REGISTERS) {
            message.accept(new ModbusWriteRequestBlueprintVisitor() {

                @Override
                public void visit(ModbusWriteRegisterRequestBlueprint blueprint) {
                    request[0] = new WriteMultipleRegistersRequest(message.getReference(), blueprint.getRegisters());
                }

                @Override
                public void visit(ModbusWriteCoilRequestBlueprint blueprint) {
                    throw new IllegalStateException();
                }
            });

        } else if (message.getFunctionCode() == ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER) {
            message.accept(new ModbusWriteRequestBlueprintVisitor() {

                @Override
                public void visit(ModbusWriteRegisterRequestBlueprint blueprint) {
                    Register[] registers = blueprint.getRegisters();
                    if (registers.length != 1) {
                        throw new IllegalArgumentException("Must provide single register with WRITE_SINGLE_REGISTER");
                    }
                    request[0] = new WriteSingleRegisterRequest(message.getReference(), registers[0]);
                }

                @Override
                public void visit(ModbusWriteCoilRequestBlueprint blueprint) {
                    throw new IllegalStateException();
                }
            });
        } else {
            throw new IllegalArgumentException(String.format("Unexpected function code %s", message.getFunctionCode()));
        }
        request[0].setUnitID(message.getUnitID());
        request[0].setProtocolID(message.getProtocolID());

        return request[0];
    }

    private ModbusTransaction createTransactionForEndpoint(ModbusSlaveEndpoint endpoint) {
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
        transaction.setRetryDelayMillis(
                connectionFactory.getEndpointPoolConfiguration(endpoint).getPassivateBorrowMinMillis());
        return transaction;
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

    @Override
    public void executeOneTimePoll(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message,
            ReadCallback callback) {
        Optional<ModbusSlaveConnection> connection = borrowConnection(endpoint);

        try {
            if (!connection.isPresent()) {
                logger.warn("Not connected to endpoint {}-- aborting request {}", endpoint, message);
                callback.internalUpdateReadErrorItem(message, new ModbusConnectionException(endpoint));
            }
            ModbusTransaction transaction = createTransactionForEndpoint(endpoint);
            ModbusRequest request = createRequest(message);
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
                callback.internalUpdateReadErrorItem(message, e);
            }
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}) {}", response.getFunctionCode(), response.getHexMessage());
            if ((response.getTransactionID() != transaction.getTransactionID()) && !response.isHeadless()) {
                logger.warn(
                        "Transaction id of the response does not match request {}.  Endpoint {}. Connection: {}. Ignoring response.",
                        request, endpoint, connection);
                callback.internalUpdateReadErrorItem(message, new ModbusUnexpectedTransactionIdException());
            }

            invokeCallbackWithResponse(message, callback, response);
        } finally {
            returnConnection(endpoint, connection);
        }
    }

    @Override
    public PollTask registerRegularPoll(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message,
            long pollPeriodMillis, ReadCallback callback) {
        ScheduledFuture<?> future = scheduledThreadPoolExecutor.scheduleAtFixedRate(
                () -> this.executeOneTimePoll(endpoint, message, callback), 0, pollPeriodMillis, TimeUnit.MILLISECONDS);
        PollTask task = new PollTaskImpl(endpoint);
        scheduledPollTasks.put(task, future);
        return task;
    }

    /**
     *
     * @return whether poll task was unregistered. Poll task is not unregistered in case of unexpected errors or
     *         nonexisting poll task
     */
    @Override
    public boolean unregisterRegularPoll(PollTask task) {
        // cancel poller
        ScheduledFuture<?> future = scheduledPollTasks.remove(task);
        if (future == null) {
            // No such poll task
            logger.warn("Caller tried to unregister nonexisting poll task %s", task);
            return false;
        }
        logger.info("Unregistering regular poll task %s (interrupting if necessary)", task);

        // Make sure connections to this endpoint are closed when they are returned to pool (which
        // is usually pretty soon as transactions should be relatively short-lived)
        ModbusManagerImpl.connectionFactory.disconnectOnReturn(task.getEndpoint(), System.currentTimeMillis());

        future.cancel(true);

        try {
            // Close all idle connections as well (they will be reconnected if necessary on borrow)
            connectionPool.clear(task.getEndpoint());
        } catch (Exception e) {
            logger.error("Could not clear poll task {} endpoint {}. Stack trace follows", task, task.getEndpoint(), e);
            return false;
        }

        return true;
    }

    @Override
    public void writeCommand(ModbusSlaveEndpoint endpoint, ModbusWriteRequestBlueprint message,
            WriteCallback callback) {
        Optional<ModbusSlaveConnection> connection = borrowConnection(endpoint);

        try {
            ModbusTransaction transaction = createTransactionForEndpoint(endpoint);
            ModbusRequest request = createRequest(message);
            transaction.setRequest(request);
            try {
                transaction.execute();
            } catch (ModbusException e) {
                // Note, one could catch ModbusIOException and ModbusSlaveException if more detailed
                // exception handling is required. For now, all exceptions are handled the same way with writes.
                logger.error("Error when executing write request ({}): {} {}", request, e.getClass().getName(),
                        e.getMessage());
                invalidate(endpoint, connection);
                // set connection to null such that it is not returned to pool
                connection = null;
                callback.internalUpdateWriteError(message, e);
            }
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}) {}", response.getFunctionCode(), response.getHexMessage());
            if ((response.getTransactionID() != transaction.getTransactionID()) && !response.isHeadless()) {
                logger.warn(
                        "Transaction id of the response does not match request {}.  Endpoint {}. Connection: {}. Ignoring response.",
                        request, endpoint, connection);
                callback.internalUpdateWriteError(message, new ModbusUnexpectedTransactionIdException());
            }

            callback.internalUpdateResponse(message, response);
        } finally {
            returnConnection(endpoint, connection);
        }
    }

    public void setDefaultPoolConfigurationFactory(
            Function<ModbusSlaveEndpoint, EndpointPoolConfiguration> defaultPoolConfigurationFactory) {
        connectionFactory.setDefaultPoolConfigurationFactory(defaultPoolConfigurationFactory);
    }

    @Override
    public void setEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint, EndpointPoolConfiguration configuration) {
        connectionFactory.setEndpointPoolConfiguration(endpoint, configuration);
    }

    @Override
    public @NotNull EndpointPoolConfiguration getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint) {
        return connectionFactory.getEndpointPoolConfiguration(endpoint);
    }

}
