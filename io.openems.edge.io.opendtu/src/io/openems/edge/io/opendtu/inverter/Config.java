package io.openems.edge.io.opendtu.inverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.meter.api.MeterType;


@ObjectClassDefinition(//
		name = "openDTU Hoymiles Inverter", //
		description = "Implements the openDTU for Hoymiles Inverter.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "io0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";
	
	@AttributeDefinition(name = "Username", description = "Username for openDTU to make settings possible")
	String username() default "";
	
	@AttributeDefinition(name = "Password", description = "Password for oprnDTU to make settings possible",  type = AttributeType.PASSWORD)
	String password() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;
	
    @AttributeDefinition(name = "Inverter Serial Number connected to Phase L1", description = "The serial number of the inverter connected to the DTU on Phase 1. Leave empty if none")
    String serialNumberL1() default "";
    
    @AttributeDefinition(name = "Inverter Serial Number connected to Phase L2", description = "The serial number of the inverter connected to the DTU on Phase 2. Leave empty if none")
    String serialNumberL2() default "";
    
    @AttributeDefinition(name = "Inverter Serial Number connected to Phase L3", description = "The serial number of the inverter connected to the DTU on Phase 3. Leave empty if none")
    String serialNumberL3() default "";    

	@AttributeDefinition(name = "IP-Address", description = "The IP address of the openDTU.")
	String ip();
    
    @AttributeDefinition(name = "Single inverter Initial Power Limit", description = "The initial power limit per inverter setting in percent. Default 100%")
    int relativePowerLimit() default 100;
    
    @AttributeDefinition(name = "Single inverter Initial Power Limit", description = "The initial power limit per inverter setting in Watt")
    int absolutePowerLimit();
    
    @AttributeDefinition(name = "Single inverter Absolute Power Limit Delay", description = "This defines in which Time (in Seconds) the PowerLimit shall be set.")
    int delay() default 30;

	@AttributeDefinition(name = "Meter-Type", description = "What is measured by this DTU?")
	MeterType type() default MeterType.PRODUCTION;

	String webconsole_configurationFactory_nameHint() default "IO openDTU Device [{id}]";
}