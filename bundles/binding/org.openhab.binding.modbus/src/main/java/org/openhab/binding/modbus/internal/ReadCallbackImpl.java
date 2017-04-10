package org.openhab.binding.modbus.internal;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.State;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ReadCallback;
import org.openhab.io.transport.modbus.internal.ModbusManagerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.msg.ReadCoilsResponse;
import net.wimpi.modbus.msg.ReadInputDiscretesResponse;
import net.wimpi.modbus.msg.ReadInputRegistersResponse;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.procimg.InputRegister;
import net.wimpi.modbus.util.BitVector;

public class ReadCallbackImpl implements ReadCallback {

    private static final Logger logger = LoggerFactory.getLogger(ReadCallbackImpl.class);

    private State[] booleanToBooleanLikeStateCandidates(boolean boolValue) {
        State[] stateCandidatesBeforeTransformation;
        stateCandidatesBeforeTransformation = new State[] { boolValue ? OnOffType.ON : OnOffType.OFF,
                boolValue ? OpenClosedType.OPEN : OpenClosedType.CLOSED };
        return stateCandidatesBeforeTransformation;
    }

    private void updateFromRegisters(ReadCallbackUsingIOConnection reader, ItemIOConnection connection,
            InputRegister[] registers) {
        String valueType = connection.getEffectiveValueType();
        State numericState = ModbusManagerImpl.extractStateFromRegisters(registers, connection.getIndex(), valueType);

        List<State> stateCandidatesForTransformation = new LinkedList<>();
        stateCandidatesForTransformation.add(numericState);
        if (connection.supportBooleanLikeState()) {
            boolean boolValue = !numericState.equals(DecimalType.ZERO);
            Stream.of(booleanToBooleanLikeStateCandidates(boolValue))
                    .forEach(s -> stateCandidatesForTransformation.add(s));

        }
        for (State newState : stateCandidatesForTransformation) {
            boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
            if (connection.supportsState(newState, stateChanged)) {
                logger.trace("{}: Updating state {} (changed={}) matched ItemIOConnection {}.", request, newState,
                        stateChanged, connection);
                Transformation transformation = connection.getTransformation();
                State transformedState = transformation == null ? newState
                        : transformation.transformState(connection.getAcceptedDataTypes(), newState);
                if (transformedState != null) {
                    reader.internalUpdateItem(request, connection, transformedState);
                    connection.setPreviouslyPolledState(newState);
                    break;
                }
            } else {
                logger.trace("{}: Not updating since state {} (changed={}) not supported by ItemIOConnection {}.",
                        request, newState, stateChanged, connection);
            }
        }
    }

    private void updateFromBits(ReadCallbackUsingIOConnection reader, ItemIOConnection connection, BitVector bits) {
        boolean booleanState = bits.getBit(connection.getIndex());
        State[] stateCandidatesForTransformation;
        if (connection.supportBooleanLikeState()) {
            stateCandidatesForTransformation = booleanToBooleanLikeStateCandidates(booleanState);
        } else {
            stateCandidatesForTransformation = new State[] {
                    booleanState ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO };
        }
        logger.trace("{}: Trying out IOConnection {} with state variations {}", message, connection,
                stateCandidatesForTransformation);
        for (State newState : stateCandidatesForTransformation) {
            boolean stateChanged = !newState.equals(connection.getPreviouslyPolledState());
            if (connection.supportsState(newState, stateChanged)) {
                Transformation transformation = connection.getTransformation();
                State transformedState = transformation == null ? newState
                        : transformation.transformState(connection.getAcceptedDataTypes(), newState);
                if (transformedState != null) {
                    logger.trace("{}: Updating state {} (changed={}) matched ItemIOConnection {}.", request, newState,
                            stateChanged, connection);
                    reader.internalUpdateItem(request, connection, transformedState);
                    connection.setPreviouslyPolledState(newState);
                    break;
                }
            } else {
                logger.trace("{}: Not updating since state {} (changed={}) not supported by ItemIOConnection {}.",
                        request, newState, stateChanged, connection);
            }
        }
        logger.trace("{}: Finished trying out IOConnection {} with state variations {}", request, connection,
                stateCandidatesForTransformation);
    }

    @Override
    public void internalUpdateReadErrorItem(ModbusReadRequestBlueprint request, Exception error) {

    }

    @Override
    public void internalUpdateItem(ModbusReadRequestBlueprint request, InputRegister[] registers) {
        List<ItemIOConnection> connections = reader.getItemIOConnections();
        for (ItemIOConnection connection : connections) {
            InputRegister[] registers;
            if (request.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_REGISTERS) {
                registers = ((ReadInputRegistersResponse) response).getRegisters();

            } else if (request.getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                registers = ((ReadMultipleRegistersResponse) response).getRegisters();
            } else {
                throw new IllegalStateException();
            }
            updateFromRegisters(request, connection, registers);
        }
    }

    @Override
    public void internalUpdateItem(ModbusReadRequestBlueprint request, BitVector coils) {
        List<ItemIOConnection> connections = reader.getItemIOConnections();
        for (ItemIOConnection connection : connections) {
            BitVector bits;
            if (request.getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
                bits = ((ReadCoilsResponse) response).getCoils();
            } else if (request.getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
                bits = ((ReadInputDiscretesResponse) response).getDiscretes();
            } else {
                throw new IllegalStateException();
            }

            if (connection.getIndex() >= request.getDataLength()) {
                logger.warn(
                        "IO connection {} read index '{}' is out-of-bound. Polled data length is only {} bits."
                                + " Check your configuration!",
                        connection, connection.getIndex(), request.getDataLength());
                continue;
            }
            updateFromBits(request, connection, bits);
        }
    }

}
