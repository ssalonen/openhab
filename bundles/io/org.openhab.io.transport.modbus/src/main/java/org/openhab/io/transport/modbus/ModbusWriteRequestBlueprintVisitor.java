package org.openhab.io.transport.modbus;

public interface ModbusWriteRequestBlueprintVisitor {

    public void visit(ModbusWriteCoilRequestBlueprint blueprint);

    public void visit(ModbusWriteRegisterRequestBlueprint blueprint);

}
