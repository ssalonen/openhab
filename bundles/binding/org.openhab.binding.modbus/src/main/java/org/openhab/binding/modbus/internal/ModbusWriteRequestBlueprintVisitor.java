package org.openhab.binding.modbus.internal;

public interface ModbusWriteRequestBlueprintVisitor {

    public void visit(ModbusWriteCoilRequestBlueprint blueprint);

    public void visit(ModbusWriteRegisterRequestBlueprint blueprint);

}
