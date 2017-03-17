package org.openhab.binding.modbus.internal;

import net.wimpi.modbus.Modbus;

public class ModbusRequestBlueprintImpl implements ModbusReadRequestBlueprint {

    private int reference;
    private int dataLength;
    private int unitID;
    private ModbusReadFunctionCode functionCode;
    private int protocolID = Modbus.DEFAULT_PROTOCOL_ID;

    public ModbusRequestBlueprintImpl(int reference, int dataLength, int unitID, ModbusReadFunctionCode functionCode) {
        this.reference = reference;
        this.dataLength = dataLength;
        this.unitID = unitID;
        this.functionCode = functionCode;
    }

    public ModbusRequestBlueprintImpl(int reference, int dataLength, int unitID, ModbusReadFunctionCode functionCode,
            int protocolID) {
        this(reference, dataLength, unitID, functionCode);
        this.protocolID = protocolID;
    }

    @Override
    public int getReference() {
        return reference;
    }

    @Override
    public int getDataLength() {
        return dataLength;
    }

    @Override
    public int getUnitID() {
        return unitID;
    }

    @Override
    public ModbusReadFunctionCode getFunctionCode() {
        return functionCode;
    }

    @Override
    public int getProtocolID() {
        return protocolID;
    }

}
