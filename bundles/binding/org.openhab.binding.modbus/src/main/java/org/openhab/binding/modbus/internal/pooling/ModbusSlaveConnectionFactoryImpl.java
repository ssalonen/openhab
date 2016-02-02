package org.openhab.binding.modbus.internal.pooling;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
                logger.trace("Created connection for endpoint {}", modbusSerialSlavePoolingKey);
                return connection;
            }

            @Override
            public ModbusSlaveConnection visit(ModbusTCPSlaveEndpoint key) {
                InetAddress address = getInetAddress(key);
                if (address == null) {
                    return null;
                }
                TCPMasterConnection connection = new TCPMasterConnection(address, key.getPort());
                logger.trace("Created connection for endpoint {}", key);
                return connection;
            }

            @Override
            public ModbusSlaveConnection visit(ModbusUDPSlaveEndpoint key) {
                InetAddress address = getInetAddress(key);
                if (address == null) {
                    return null;
                }
                UDPMasterConnection connection = new UDPMasterConnection(address, key.getPort());
                logger.trace("Created connection for endpoint {}", key);
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
    public void activateObject(ModbusSlaveEndpoint key, PooledObject<ModbusSlaveConnection> obj) throws Exception {
        if (obj.getObject() == null) {
            return;
        }
        Exception exc = null;
        try {
            obj.getObject().connect();
        } catch (Exception e) {
            exc = e;
            logger.error("Error connecting connection {} for endpoint {}", obj.getObject(), key, e);
        }
        if (exc != null) {
            logger.trace("Activated connection {} for endpoint {} -- but error occurred with connect()",
                    obj.getObject(), key);
        } else {
            logger.trace("Activated connection {} for endpoint {} -- connect() ok", obj.getObject(), key);
        }
    }

    @Override
    public void passivateObject(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj) {
        if (obj.getObject() == null) {
            return;
        }
        logger.trace("Passivating connection {} for endpoint {}", obj.getObject(), endpoint);
        closeIfNotSerialEndpoint(endpoint, obj);
    }

    @Override
    public boolean validateObject(ModbusSlaveEndpoint key, PooledObject<ModbusSlaveConnection> p) {
        boolean valid = p.getObject() != null && p.getObject().isConnected();
        logger.trace("Validated endpoint {} connection {} -> {}", key, p.getObject(), valid);
        return valid;
    }

    private void closeIfNotSerialEndpoint(ModbusSlaveEndpoint endpoint, PooledObject<ModbusSlaveConnection> obj) {
        final PooledObject<ModbusSlaveConnection> finalObj = obj;
        endpoint.accept(new ModbusSlaveConnectionVisitor<Object>() {
            @Override
            public Object visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
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

}