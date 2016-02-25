package org.openhab.binding.modbus.internal.pooling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.openhab.binding.modbus.internal.ModbusSlaveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.net.SerialConnection;
import net.wimpi.modbus.net.TCPMasterConnection;
import net.wimpi.modbus.net.UDPMasterConnection;

public class ModbusSlaveConnectionFactoryImpl
        extends BaseKeyedPooledObjectFactory<ModbusSlaveEndpoint, ModbusSlaveConnection> {

    private static final Logger logger = LoggerFactory.getLogger(ModbusSlaveConnectionFactoryImpl.class);
    private Map<ModbusSlaveEndpoint, EndpointPoolConfiguration> endpointPoolConfigs;
    private Map<ModbusSlaveEndpoint, Long> lastBorrowMillis = new HashMap<ModbusSlaveEndpoint, Long>();
    private Map<ModbusSlaveEndpoint, Long> lastConnectMillis = new HashMap<ModbusSlaveEndpoint, Long>();

    private InetAddress getInetAddress(ModbusIPSlaveEndpoint key) {
        try {
            return InetAddress.getByName(key.getAddress());
        } catch (UnknownHostException e) {
            logger.error("KeyedPooledModbusSlaveConnectionFactory: Unknown host: {}. Connection creation failed.",
                    e.getMessage());
            return null;
        }
    }

    @Override
    public ModbusSlaveConnection create(ModbusSlaveEndpoint endpoint) throws Exception {
        return endpoint.accept(new ModbusSlaveConnectionVisitor<ModbusSlaveConnection>() {
            @Override
            public ModbusSlaveConnection visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                SerialConnection connection = new SerialConnection(modbusSerialSlavePoolingKey.getSerialParameters());
                logger.trace("Created connection {} for endpoint {}", connection, modbusSerialSlavePoolingKey);
                return connection;
            }

            @Override
            public ModbusSlaveConnection visit(ModbusTCPSlaveEndpoint key) {
                InetAddress address = getInetAddress(key);
                if (address == null) {
                    return null;
                }
                TCPMasterConnection connection = new TCPMasterConnection(address, key.getPort());
                logger.trace("Created connection {} for endpoint {}", connection, key);
                return connection;
            }

            @Override
            public ModbusSlaveConnection visit(ModbusUDPSlaveEndpoint key) {
                InetAddress address = getInetAddress(key);
                if (address == null) {
                    return null;
                }
                UDPMasterConnection connection = new UDPMasterConnection(address, key.getPort());
                logger.trace("Created connection {} for endpoint {}", connection, key);
                return connection;
            }
        });
    }

    @Override
    public PooledObject<ModbusSlaveConnection> wrap(ModbusSlaveConnection connection) {
        return new DefaultPooledObject<ModbusSlaveConnection>(connection);
    }

    @Override
    public void destroyObject(ModbusSlaveEndpoint endpoint, final PooledObject<ModbusSlaveConnection> obj) {
        obj.getObject().resetConnection();
    }

    @Override
    public void activateObject(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj) throws Exception {
        if (obj.getObject() == null) {
            return;
        }
        try {
            ModbusSlaveConnection connection = obj.getObject();
            EndpointPoolConfiguration config = endpointPoolConfigs.get(endpoint);

            if (connection.isConnected()) {
                if (config != null) {
                    long waited = waitAtleast(lastBorrowMillis.get(endpoint), config.getInterBorrowDelayMillis());
                    logger.trace(
                            "Waited {}ms (interBorrowDelayMillis {}ms) before giving returning connection {} for endpoint {}, to allow delay between transactions.",
                            waited, config.getInterBorrowDelayMillis(), obj.getObject(), endpoint);
                }
                return;
            } else {
                // invariant: !connection.isConnected()
                tryConnectDisconnected(endpoint, obj, connection, config);
            }
            lastBorrowMillis.put(endpoint, System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Error connecting connection {} for endpoint {}", obj.getObject(), endpoint, e.getMessage());
        }
    }

    @Override
    public void passivateObject(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj) {
        if (obj.getObject() == null) {
            return;
        }
        logger.trace("Passivating connection {} for endpoint {}...", obj.getObject(), endpoint);
        closeIfNotSerialEndpoint(endpoint, obj);
        logger.trace("...Passivated connection {} for endpoint {}", obj.getObject(), endpoint);
    }

    @Override
    public boolean validateObject(ModbusSlaveEndpoint key, PooledObject<ModbusSlaveConnection> p) {
        boolean valid = p.getObject() != null && p.getObject().isConnected();
        logger.trace("Validating endpoint {} connection {} -> {}", key, p.getObject(), valid);
        return valid;
    }

    private void closeIfNotSerialEndpoint(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj) {
        final PooledObject<ModbusSlaveConnection> finalObj = obj;
        endpoint.accept(new ModbusSlaveConnectionVisitor<Object>() {
            @Override
            public Object visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                logger.trace("Not reseting connection {} for endpoint {} since we have Serial endpoint",
                        finalObj.getObject(), modbusSerialSlavePoolingKey);
                return null;
            }

            @Override
            public Object visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey) {
                finalObj.getObject().resetConnection();
                logger.trace("Reseted connection {} for endpoint {}", finalObj.getObject(), modbusIPSlavePoolingKey);
                return null;
            }

            @Override
            public Object visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey) {
                finalObj.getObject().resetConnection();
                logger.trace("Reseted connection {} for endpoint {}", finalObj.getObject(), modbusUDPSlavePoolingKey);
                return null;
            }

        });
    }

    public Map<ModbusSlaveEndpoint, EndpointPoolConfiguration> getEndpointPoolConfigs() {
        return endpointPoolConfigs;
    }

    public void setEndpointPoolConfigs(Map<ModbusSlaveEndpoint, EndpointPoolConfiguration> endpointPoolConfigs) {
        this.endpointPoolConfigs = endpointPoolConfigs;
    }

    private void tryConnectDisconnected(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj,
            ModbusSlaveConnection connection, EndpointPoolConfiguration config) throws Exception {
        int tryIndex = 0;
        Long lastConnect = lastConnectMillis.get(endpoint);
        int maxTries = config == null ? 1 : config.getConnectMaxTries();
        do {
            try {
                if (config != null) {
                    long waited = waitAtleast(lastConnect,
                            Math.max(config.getInterConnectDelayMillis(), config.getInterBorrowDelayMillis()));
                    if (waited > 0) {
                        logger.trace(
                                "Waited {}ms (interConnectDelayMillis {}ms, interBorrowDelayMillis {}ms) before "
                                        + "connecting disconnected connection {} for endpoint {}, to allow delay "
                                        + "between connections re-connects",
                                waited, config.getInterConnectDelayMillis(), config.getInterBorrowDelayMillis(),
                                obj.getObject(), endpoint);
                    }

                }
                connection.connect();
                lastConnectMillis.put(endpoint, System.currentTimeMillis());
                break;
            } catch (Exception e) {
                tryIndex++;
                logger.error("connect try {}/{} error: {}. Connection {}. Endpoint {}", tryIndex, maxTries,
                        e.getMessage(), connection, endpoint);
                if (tryIndex >= maxTries) {
                    logger.error("re-connect reached max tries {}, throwing last error: {}. Connection {}. Endpoint {}",
                            maxTries, e.getMessage(), connection, endpoint);
                    throw e;
                }
                lastConnect = System.currentTimeMillis();
            }
        } while (true);
    }

    private long waitAtleast(Long lastOperation, long waitMillis) {
        if (lastOperation == null) {
            return 0;
        }
        long millisSinceLast = System.currentTimeMillis() - lastOperation;
        long millisToWaitStill = Math.min(waitMillis, Math.max(0, waitMillis - millisSinceLast));
        try {
            Thread.sleep(millisToWaitStill);
        } catch (InterruptedException e) {
            logger.error("wait interrupted: {}", e.getMessage());
        }
        return millisToWaitStill;
    }

}