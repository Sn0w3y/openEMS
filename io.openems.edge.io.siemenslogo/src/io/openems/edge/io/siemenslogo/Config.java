package io.openems.edge.io.siemenslogo;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Siemens LOGO!", //
		description = "Siemens LOGO! 8 digital Input/Output")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "io0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus write address offset", description = "Address offset in LOGO! for writing outputs / relays. This is where the virtual addresses start, e.g. 808 for virtual address 101.0 in Logo!")
	int modbusOffsetWriteAddress() default 800;

	@AttributeDefinition(name = "Modbus read address offset", description = "Address offset in LOGO! for reading inputs (DI1-4). This is where the virtual addresses start, e.g. 808 for virtual address 110.0 in Logo!")
	int modbusOffsetReadAddress() default 880;

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default 1;

	@AttributeDefinition(name = "Modbus target filter", description = "This is auto-generated by 'Modbus-ID'.")
	String Modbus_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Siemens LOGO! [{id}]";
}