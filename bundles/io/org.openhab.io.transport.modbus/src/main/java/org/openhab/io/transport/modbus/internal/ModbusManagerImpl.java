package org.openhab.io.transport.modbus.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.StandardToStringStyle;
import org.apache.commons.lang.builder.ToStringBuilder;
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
import org.openhab.core.types.Command;
import org.openhab.io.transport.modbus.ModbusManager;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteFunctionCode;
import org.openhab.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteRequestBlueprintVisitor;
import org.openhab.io.transport.modbus.ReadCallback;
import org.openhab.io.transport.modbus.WriteCallback;
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
import net.wimpi.modbus.net.SerialConnection;
import net.wimpi.modbus.net.TCPMasterConnection;
import net.wimpi.modbus.net.UDPMasterConnection;
import net.wimpi.modbus.procimg.InputRegister;
import net.wimpi.modbus.procimg.Register;

public class ModbusManagerImpl implements ModbusManager {

    public static class PollTaskImpl implements PollTask {

        private static StandardToStringStyle toStringStyle = new StandardToStringStyle();

        static {
            toStringStyle.setUseShortClassName(true);
        }

        private ModbusSlaveEndpoint endpoint;
        private ModbusReadRequestBlueprint message;
        private ReadCallback callback;

        public PollTaskImpl(ModbusSlaveEndpoint endpoint, ModbusReadRequestBlueprint message, ReadCallback callback) {
            this.endpoint = endpoint;
            this.message = message;
            this.callback = callback;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (obj.getClass() != getClass()) {
                return false;
            }
            PollTaskImpl rhs = (PollTaskImpl) obj;
            return new EqualsBuilder().append(endpoint, rhs.endpoint).append(message, rhs.message)
                    .append(callback, rhs.callback).isEquals();
        }

        @Override
        public int hashCode() {
            return new HashCodeBuilder(1541, 81).append(endpoint).append(message).append(callback).toHashCode();
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this, toStringStyle).append("endpoint", endpoint).append("message", message)
                    .append("callback", callback).toString();
        }

        @Override
        public ModbusSlaveEndpoint getEndpoint() {
            return endpoint;
        }

        @Override
        public ModbusReadRequestBlueprint getMessage() {
            return message;
        }

        @Override
        public ReadCallback getCallback() {
            return callback;
        }
    }

    private static class PollTaskUnregistered extends Exception {
        public PollTaskUnregistered(String msg) {
            super(msg);
        }

        private static final long serialVersionUID = 6939730579178506885L;

    }

    private static final Logger logger = LoggerFactory.getLogger(ModbusManagerImpl.class);

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 60ms for TCP slaves, Siemens S7 1212 PLC couldn't handle faster
     * requests with default settings.
     */
    public static final long DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS = 60;

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 35ms for Serial slaves, motivation discussed
     * here https://community.openhab.org/t/connection-pooling-in-modbus-binding/5246/111?u=ssalonen
     */
    public static final long DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS = 35;

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
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    // never "disconnect" (close/open serial port) serial connection between borrows
                    endpointPoolConfig.setReconnectAfterMillis(-1);
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
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
        try {
            if (message.getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
                callback.internalUpdateItem(message, ((ReadCoilsResponse) response).getCoils());
            } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
                callback.internalUpdateItem(message, ((ReadInputDiscretesResponse) response).getDiscretes());
            } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                callback.internalUpdateItem(message, ((ReadMultipleRegistersResponse) response).getRegisters());
            } else if (message.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                callback.internalUpdateItem(message, ((ReadMultipleRegistersResponse) response).getRegisters());
            } else {
                throw new IllegalArgumentException(
                        String.format("Unexpected function code %s", message.getFunctionCode()));
            }

        } catch (Exception e) {
            logger.error("Unhandled exception {} {}", e.getClass().getName(), e.getMessage(), e);
        }
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
        } else

        {
            throw new IllegalArgumentException(String.format("Unexpected function code %s", message.getFunctionCode()));
        }
        request[0].setUnitID(message.getUnitID());
        request[0].setProtocolID(message.getProtocolID());

        return request[0];
    }

    private ModbusTransaction createTransactionForEndpoint(ModbusSlaveEndpoint endpoint,
            Optional<ModbusSlaveConnection> connection) {
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
        if (transaction instanceof ModbusSerialTransaction) {
            ((ModbusSerialTransaction) transaction).setSerialConnection((SerialConnection) connection.get());
        } else if (transaction instanceof ModbusUDPTransaction) {
            ((ModbusUDPTransaction) transaction).setTerminal(((UDPMasterConnection) connection.get()).getTerminal());
        } else if (transaction instanceof ModbusTCPTransaction) {
            ((ModbusTCPTransaction) transaction).setConnection((TCPMasterConnection) connection.get());
        } else {
            throw new IllegalStateException();
        }
        return transaction;
    }

    Optional<ModbusSlaveConnection> borrowConnection(ModbusSlaveEndpoint endpoint) {
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

    void invalidate(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        if (!connection.isPresent()) {
            return;
        }
        connection.ifPresent(con -> {
            try {
                connectionPool.invalidateObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error invalidating connection in pool for endpoint {}. Error was: {} {}", endpoint,
                        e.getClass().getName(), e.getMessage(), e);
            }
        });
    }

    void returnConnection(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        connection.ifPresent(con -> {
            try {
                connectionPool.returnObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error returning connection to pool for endpoint {}. Error was: {} {}", endpoint,
                        e.getClass().getName(), e.getMessage(), e);
            }
        });
        logger.trace("returned connection for endpoint {}", endpoint);
    }

    private void verifyTaskIsRegistered(PollTask task) throws PollTaskUnregistered {
        if (!this.scheduledPollTasks.containsKey(task)) {
            String msg = String.format("Poll task %s is unregistered", task);
            logger.warn(msg);
            throw new PollTaskUnregistered(msg);
        }
    }

    @Override
    public void executeOneTimePoll(PollTask task) {
        executeOneTimePoll(task, true);
    }

    public void executeOneTimePoll(PollTask task, boolean manual) {
        ModbusSlaveEndpoint endpoint = task.getEndpoint();
        ModbusReadRequestBlueprint message = task.getMessage();
        ReadCallback callback = task.getCallback();

        Optional<ModbusSlaveConnection> connection = borrowConnection(endpoint); // might take a while

        try {
            if (!connection.isPresent()) {
                logger.warn("Not connected to endpoint {} -- aborting request {}", endpoint, message);
                if (!manual) {
                    verifyTaskIsRegistered(task);
                }
                callback.internalUpdateReadErrorItem(message, new ModbusConnectionException(endpoint));
            }

            ModbusTransaction transaction = createTransactionForEndpoint(endpoint, connection);
            ModbusRequest request = createRequest(message);
            transaction.setRequest(request);
            if (!manual) {
                verifyTaskIsRegistered(task);
            }

            try {
                transaction.execute();
            } catch (ModbusException e) {
                // Note, one could catch ModbusIOException and ModbusSlaveException if more detailed
                // exception handling is required. For now, all exceptions are handled the same way with writes.
                logger.error("Error when executing read request ({}): {} {}", request, e.getClass().getName(),
                        e.getMessage());
                invalidate(endpoint, connection);
                // set connection to null such that it is not returned to pool
                connection = Optional.empty();
                if (!manual) {
                    verifyTaskIsRegistered(task);
                }
                callback.internalUpdateReadErrorItem(message, e);
            }
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}, transaction ID={}) {}", response.getFunctionCode(),
                    response.getTransactionID(), response.getHexMessage());

            if (!manual) {
                verifyTaskIsRegistered(task);
            }

            if ((response.getTransactionID() != transaction.getTransactionID()) && !response.isHeadless()) {
                logger.warn(
                        "Transaction id of the response does not match request {}.  Endpoint {}. Connection: {}. Ignoring response.",
                        request, endpoint, connection);
                callback.internalUpdateReadErrorItem(message, new ModbusUnexpectedTransactionIdException());
            } else {
                invokeCallbackWithResponse(message, callback, response);
            }
        } catch (PollTaskUnregistered e) {
            logger.warn("Poll task was unregistered -- not executing/proceeding with the poll", e);
        } finally {
            returnConnection(endpoint, connection);
        }
    }

    @Override
    public void registerRegularPoll(PollTask task, long pollPeriodMillis, long initialDelayMillis) {
        ScheduledFuture<?> future = scheduledThreadPoolExecutor.scheduleAtFixedRate(() -> {
            logger.debug("Executing scheduled ({}ms) poll task {}", pollPeriodMillis, task);
            this.executeOneTimePoll(task, false);
        }, initialDelayMillis, pollPeriodMillis, TimeUnit.MILLISECONDS);
        scheduledPollTasks.put(task, future);
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
            logger.warn("Caller tried to unregister nonexisting poll task {}", task);
            return false;
        }
        logger.info("Unregistering regular poll task {} (interrupting if necessary)", task);

        // Make sure connections to this endpoint are closed when they are returned to pool (which
        // is usually pretty soon as transactions should be relatively short-lived)
        ModbusManagerImpl.connectionFactory.disconnectOnReturn(task.getEndpoint(), System.currentTimeMillis());

        future.cancel(true);

        logger.info("Poll task {} canceled", task);

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
            ModbusTransaction transaction = createTransactionForEndpoint(endpoint, connection);
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
                connection = Optional.empty();
                callback.internalUpdateWriteError(message, e);
            }
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for write (FC={}) {}", response.getFunctionCode(), response.getHexMessage());
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
    public EndpointPoolConfiguration getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint) {
        return connectionFactory.getEndpointPoolConfiguration(endpoint);
    }

    static final public String VALUE_TYPE_BIT = "bit";
    static final public String VALUE_TYPE_INT8 = "int8";
    static final public String VALUE_TYPE_UINT8 = "uint8";
    static final public String VALUE_TYPE_INT16 = "int16";
    static final public String VALUE_TYPE_UINT16 = "uint16";
    static final public String VALUE_TYPE_INT32 = "int32";
    static final public String VALUE_TYPE_UINT32 = "uint32";
    static final public String VALUE_TYPE_FLOAT32 = "float32";
    static final public String VALUE_TYPE_INT32_SWAP = "int32_swap";
    static final public String VALUE_TYPE_UINT32_SWAP = "uint32_swap";
    static final public String VALUE_TYPE_FLOAT32_SWAP = "float32_swap";

    /**
     * Read data from registers and convert the result to DecimalType
     * Interpretation of <tt>index</tt> goes as follows depending on type
     *
     * BIT:
     * - a single bit is read from the registers
     * - indices between 0...15 (inclusive) represent bits of the first register
     * - indices between 16...31 (inclusive) represent bits of the second register, etc.
     * - index 0 refers to the least significant bit of the first register
     * - index 1 refers to the second least significant bit of the first register, etc.
     * INT8:
     * - a byte (8 bits) from the registers is interpreted as signed integer
     * - index 0 refers to low byte of the first register, 1 high byte of first register
     * - index 2 refers to low byte of the second register, 3 high byte of second register, etc.
     * - it is assumed that each high and low byte is encoded in most significant bit first order
     * UINT8:
     * - same as INT8 except values are interpreted as unsigned integers
     * INT16:
     * - register with index (counting from zero) is interpreted as 16 bit signed integer.
     * - it is assumed that each register is encoded in most significant bit first order
     * UINT16:
     * - same as INT16 except values are interpreted as unsigned integers
     * INT32:
     * - registers (2 * index) and ( 2 *index + 1) are interpreted as signed 32bit integer.
     * - it assumed that the first register contains the most significant 16 bits
     * - it is assumed that each register is encoded in most significant bit first order
     * UINT32:
     * - same as UINT32 except values are interpreted as unsigned integers
     * FLOAT32:
     * - registers (2 * index) and ( 2 *index + 1) are interpreted as signed 32bit floating point number.
     * - it assumed that the first register contains the most significant 16 bits
     * - it is assumed that each register is encoded in most significant bit first order
     *
     * @param registers
     *            list of registers, each register represent 16bit of data
     * @param index
     *            zero based item index. Interpretation of this depends on type
     * @param type
     *            item type, e.g. unsigned 16bit integer (<tt>ModbusBindingProvider.VALUE_TYPE_UINT16</tt>)
     * @return number representation queried value
     * @throws IllegalArgumentException when <tt>type</tt> does not match a known type
     * @throws IndexOutOfBoundsException when <tt>index</tt> is out of bounds of registers
     *
     */
    public static DecimalType extractStateFromRegisters(InputRegister[] registers, int index, String type) {
        if (type.equals(VALUE_TYPE_BIT)) {
            return new DecimalType((registers[index / 16].toUnsignedShort() >> (index % 16)) & 1);
        } else if (type.equals(VALUE_TYPE_INT8)) {
            return new DecimalType(registers[index / 2].toBytes()[1 - (index % 2)]);
        } else if (type.equals(VALUE_TYPE_UINT8)) {
            return new DecimalType((registers[index / 2].toUnsignedShort() >> (8 * (index % 2))) & 0xff);
        } else if (type.equals(VALUE_TYPE_INT16)) {
            ByteBuffer buff = ByteBuffer.allocate(2);
            buff.put(registers[index].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getShort(0));
        } else if (type.equals(VALUE_TYPE_UINT16)) {
            return new DecimalType(registers[index].toUnsignedShort());
        } else if (type.equals(VALUE_TYPE_INT32)) {
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.put(registers[index * 2 + 0].toBytes());
            buff.put(registers[index * 2 + 1].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getInt(0));
        } else if (type.equals(VALUE_TYPE_UINT32)) {
            ByteBuffer buff = ByteBuffer.allocate(8);
            buff.position(4);
            buff.put(registers[index * 2 + 0].toBytes());
            buff.put(registers[index * 2 + 1].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getLong(0));
        } else if (type.equals(VALUE_TYPE_FLOAT32)) {
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.put(registers[index * 2 + 0].toBytes());
            buff.put(registers[index * 2 + 1].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getFloat(0));
        } else if (type.equals(VALUE_TYPE_INT32_SWAP)) {
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.put(registers[index * 2 + 1].toBytes());
            buff.put(registers[index * 2 + 0].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getInt(0));
        } else if (type.equals(VALUE_TYPE_UINT32_SWAP)) {
            ByteBuffer buff = ByteBuffer.allocate(8);
            buff.position(4);
            buff.put(registers[index * 2 + 1].toBytes());
            buff.put(registers[index * 2 + 0].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getLong(0));
        } else if (type.equals(VALUE_TYPE_FLOAT32_SWAP)) {
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.put(registers[index * 2 + 1].toBytes());
            buff.put(registers[index * 2 + 0].toBytes());
            return new DecimalType(buff.order(ByteOrder.BIG_ENDIAN).getFloat(0));
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Calculates boolean value that will be written to the device as a result of OpenHAB command
     * Used with item bound to "coil" type slaves
     *
     * @param command OpenHAB command received by the item
     * @return new boolean value to be written to the device
     */
    public static Optional<Boolean> translateCommand2Boolean(Command command) {
        if (command.equals(OnOffType.ON)) {
            return Optional.of(Boolean.TRUE);
        }
        if (command.equals(OnOffType.OFF)) {
            return Optional.of(Boolean.FALSE);
        }
        if (command.equals(OpenClosedType.OPEN)) {
            return Optional.of(Boolean.TRUE);
        }
        if (command.equals(OpenClosedType.CLOSED)) {
            return Optional.of(Boolean.FALSE);
        }
        if (command instanceof DecimalType) {
            return Optional.of(!command.equals(DecimalType.ZERO));
        }
        return Optional.empty();
    }

}
