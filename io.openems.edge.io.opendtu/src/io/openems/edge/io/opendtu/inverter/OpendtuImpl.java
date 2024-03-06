package io.openems.edge.io.opendtu.inverter;

import static io.openems.common.utils.JsonUtils.getAsJsonObject;
import static io.openems.common.utils.JsonUtils.getAsJsonArray;
import static io.openems.common.utils.JsonUtils.getAsFloat;
import static io.openems.common.utils.JsonUtils.getAsString;
import static io.openems.common.utils.JsonUtils.getAsInt;
import static java.lang.Math.round;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.http.api.BridgeHttp;
import io.openems.edge.bridge.http.api.BridgeHttpFactory;
import io.openems.edge.bridge.http.api.HttpMethod;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.MeterType;
import io.openems.edge.meter.api.SinglePhase;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)

@Component(//
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})

@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})

/*
 * ToDo:
 * 
 * - We still need to implement a Channel to set the overall Power Limit right?
 * - Please have a close look to the changes i made - We still need to check the Code :D 
 * - We still need to drink more Bavarian Beer ;)
 * 
 * Done:
 * 2024 03 05 Thomas: drank 2 Bitburger Stubbis
 * 2024 03 06 Thomas: Added modbus slave feature
 */

public class OpendtuImpl extends AbstractOpenemsComponent implements Opendtu, ElectricityMeter, OpenemsComponent,
		EventHandler, TimedataProvider, ManagedSymmetricPvInverter {

	private final Logger log = LoggerFactory.getLogger(OpendtuImpl.class);

	@Reference()
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	// This Reference MF is needed.
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	private final CalculateEnergyFromPower calculateActualEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	private String baseUrl;
	private String encodedAuth;
	private Config config;
	private int delay;

	private Boolean isInitialPowerLimitSet = false;
	private MeterType meterType = null;
	private SinglePhase phase = null;
	private int numInverters = 0;

	private List<InverterData> validInverters = new ArrayList<>();
	private Map<String, InverterData> inverterDataMap = new HashMap<>();

	public OpendtuImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				Opendtu.ChannelId.values() //
		);
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		this.config = config;
		
		// Call the superclass activate method to initialize the component with the
		// provided configurations.
		super.activate(context, config.id(), config.alias(), config.enabled());

		// Encode authentication credentials for HTTP communication with the inverter.
		String auth = config.username() + ":" + config.password();
		this.encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

		// Collect inverter data based on the configuration and initialize the HTTP
		// bridge.
		this.validInverters = InverterData.collectInverterData(config);
		this.numInverters = this.validInverters.size();
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();

		// Set meter type and debounce delay as specified in the configuration.
		this.meterType = config.type();
		this.delay = config.delay();

		// Subscribe to live data updates for each configured inverter.
		for (InverterData inverter : this.validInverters) {
			this.inverterDataMap.put(inverter.getSerialNumber(), inverter);
			String inverterStatusUrl = "/api/livedata/status?inv=" + inverter.getSerialNumber();
			if (this.isEnabled()) {
				this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + inverterStatusUrl, this::processHttpResult);
			}
		}

		// Set initial power limits for each inverter, if specified in the
		// configuration.
		if (!this.isInitialPowerLimitSet) {
			Map<String, String> properties = Map.of("Authorization", "Basic " + this.encodedAuth, "Content-Type",
					"application/x-www-form-urlencoded");
			for (InverterData inverter : this.validInverters) {
				// Skip setting power limit if both limits are unspecified.
				if (config.absolutePowerLimit() == -1 && config.relativePowerLimit() == -1) {
					if (this.config.debug()) {
						this.log.info("Skipping power limit setting for inverter [{}] as both limits are unspecified",
								inverter.getSerialNumber());
					}
					continue;
				}

				// Determine and set the appropriate power limit based on configuration.
				this.determineAndSetPowerLimit(config, inverter, properties);
			}
			this.isInitialPowerLimitSet = true;
		}
	}

	private void determineAndSetPowerLimit(Config config, InverterData inverter, Map<String, String> properties) {
	    Integer limitValue = null; // Use null to represent no action.
	    Integer limitType = null; // Use null to indicate no limit type has been determined.

	    // Determine if setting absolute or relative power limit.
	    if (config.absolutePowerLimit() != -1) {
	        limitValue = config.absolutePowerLimit();
	        limitType = 0; // Absolute limit type.
	    } else if (config.relativePowerLimit() != -1) {
	        limitValue = config.relativePowerLimit();
	        limitType = 1; // Relative limit type.
	    }

	    // Check if a limit has been determined. If not, do nothing.
	    if (limitValue == null || limitType == null) {
	        return; // Exit the method early if there's no configuration.
	    }

	    // Final or effectively final copies for use in lambda expression
	    final Integer finalLimitType = limitType;
	    final Integer finalLimitValue = limitValue;

	    // Prepare and send HTTP request to set the power limit.
	    String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":%d, \"limit_value\":%d}",
	            inverter.getSerialNumber(), finalLimitType, finalLimitValue);

	    String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);

	    BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config", HttpMethod.POST,
	            BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, formattedPayload, properties);

	    this.httpBridge.request(endpoint)
	            .thenAccept(response -> this.handlePowerLimitResponse(inverter, finalLimitType, finalLimitValue))
	            .exceptionally(ex -> this.handlePowerLimitError(inverter, ex));
	}


	private void handlePowerLimitResponse(InverterData inverter, int limitType, int limitValue) {
		// Log success message
		if (this.config.debug()) {
			this.log.info("Power limit successfully set for inverter [{}]. LimitType: {}, LimitValue: {}",
					inverter.getSerialNumber(), limitType, limitValue);
		}
		// Update the respective channel based on the limit type
		if (limitType == 0) { // Absolute limit type
			this.channel(Opendtu.ChannelId.ABSOLUTE_LIMIT).setNextValue(limitValue);
			// Optionally clear the relative limit channel if setting an absolute limit
			this.channel(Opendtu.ChannelId.RELATIVE_LIMIT).setNextValue(null);
		} else { // Relative limit type
			this.channel(Opendtu.ChannelId.RELATIVE_LIMIT).setNextValue(limitValue);
			// Optionally clear the absolute limit channel if setting a relative limit
			this.channel(Opendtu.ChannelId.ABSOLUTE_LIMIT).setNextValue(null);
		}
	}

	// Method to handle errors during the setting of power limit
	private Void handlePowerLimitError(InverterData inverter, Throwable ex) {
		// Log the error
		if (this.config.debug()) {
			this.log.error("Error setting power limit for inverter [{}]: {}", inverter.getSerialNumber(),
					ex.getMessage());
		}
		// Indicate a fault in setting power limit
		this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);
		// This method must return null because it's used as a lambda in `exceptionally`
		return null;
	}

	private void processHttpResult(JsonElement result, Throwable error) {
		this._setSlaveCommunicationFailed(result == null);

		Integer power = null;
		Integer reactivepower = null;
		Integer voltage = null;
		Integer current = null;
		Integer frequency = null;
		Integer totalPower = null; // Power over all inverters connected to the DTU
		String serialNumber = null;
		Integer powerLimitPerPhaseAbsolute = null;
		Integer powerLimitPerPhaseRelative = null;

		if (error != null) {
			this.logDebug(this.log, error.getMessage());
		} else {
			try {
				var response = getAsJsonObject(result);

				var totalObject = getAsJsonObject(response, "total");
				var totalPowerObject = getAsJsonObject(totalObject, "Power");
				totalPower = round(getAsFloat(totalPowerObject, "v"));

				// Processing inverters
				var invertersArray = getAsJsonArray(response, "inverters");
				if (invertersArray.size() == 0) {
					return;
				}

				var inverterResponse = getAsJsonObject(invertersArray.get(0));
				serialNumber = getAsString(inverterResponse, "serial");

				// Limit information
				powerLimitPerPhaseAbsolute = round(getAsFloat(inverterResponse, "limit_absolute"));
				powerLimitPerPhaseRelative = round(getAsFloat(inverterResponse, "limit_relative"));

				// AC data
				var acData = getAsJsonObject(inverterResponse, "AC");
				var ac0Data = getAsJsonObject(acData, "0");

				var powerObj = getAsJsonObject(ac0Data, "Power");
				power = round(getAsFloat(powerObj, "v"));

				var reactivePowerObj = getAsJsonObject(ac0Data, "ReactivePower");
				reactivepower = round(getAsFloat(reactivePowerObj, "v"));

				var voltageObj = getAsJsonObject(ac0Data, "Voltage");
				voltage = round(getAsFloat(voltageObj, "v") * 1000);

				var currentObj = getAsJsonObject(ac0Data, "Current");
				current = round(getAsFloat(currentObj, "v") * 1000);

				var frequencyObj = getAsJsonObject(ac0Data, "Frequency");
				frequency = round(getAsInt(frequencyObj, "v") * 1000);

			} catch (OpenemsNamedException e) {
				this.logError(this.log, "Error processing HTTP result: " + e.getMessage());
				this._setSlaveCommunicationFailed(true);
			}
		}

		InverterData inverterData = this.inverterDataMap.get(serialNumber);
		inverterData.setPower(power);
		inverterData.setCurrent(current);
		inverterData.setVoltage(voltage);
		inverterData.setFrequency(frequency);
		inverterData.setCurrentPowerLimitAbsolute(powerLimitPerPhaseAbsolute);
		inverterData.setCurrentPowerLimitRelative(powerLimitPerPhaseRelative);
		String phase = inverterData.getPhase();

		switch (phase) {
		case "L1":
			this._setActivePowerL1(power);
			this._setVoltageL1(voltage);
			this._setCurrentL1(current);
			this._setReactivePowerL1(reactivepower);
			break;
		case "L2":
			this._setActivePowerL2(power);
			this._setVoltageL2(voltage);
			this._setCurrentL2(current);
			this._setReactivePowerL2(reactivepower);
			break;
		case "L3":
			this._setActivePowerL3(power);
			this._setVoltageL3(voltage);
			this._setCurrentL3(current);
			this._setReactivePowerL3(reactivepower);
			break;
		}

		this._setFrequency(frequency); // We assume frequency to be equal on all phases/inverters
		this._setActivePower(totalPower); // ActivePower over the whole cluster
		this._setSlaveCommunicationFailed(false);
	}

	@Deactivate
	protected void deactivate() {
		this.httpBridgeFactory.unget(this.httpBridge);
		this.httpBridge = null;
		super.deactivate();
	}

	@Override
	public String debugLog() {
		var b = new StringBuilder();
		b.append(this.getActivePowerChannel().value().asString());
		return b.toString();
	}

	@Override
	public void handleEvent(Event event) {
		this.calculateEnergy();
		if (!this.isEnabled()) {
			return;
		}

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.updateLimitStatusChannels();
			break;
		}

	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		var actualPower = this.getActivePower().get();
		if (actualPower == null) {
			// Not available
			this.calculateActualEnergy.update(null);
		} else if (actualPower > 0) {
			this.calculateActualEnergy.update(actualPower);
		} else {
			this.calculateActualEnergy.update(0);
		}

	}

	// Initialize debouncer with the config delay
	private final Debouncer debouncer = new Debouncer(this.delay, TimeUnit.SECONDS);

	public void setActivePowerLimit(int powerLimit) throws OpenemsNamedException {
		int individualPowerLimit = Math.round(powerLimit / this.numInverters);

		this.inverterDataMap.forEach((serialNumber, inverterData) -> {
			// Debounce the limit setting task
			this.debouncer.debounce(serialNumber, () -> {
				if ("Pending".equals(inverterData.getlimitSetStatus())) {
					if (this.config.debug()) {
						this.log.info("Still Pending to set limit for serial [{}] ", serialNumber);
					}
					return;
				}

				String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":0, \"limit_value\":%d}",
						serialNumber, individualPowerLimit);
				String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);
				Map<String, String> properties = Map.of("Authorization", "Basic " + this.encodedAuth, "Content-Type",
						"application/x-www-form-urlencoded");

				BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config",
						HttpMethod.POST, BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT,
						formattedPayload, properties);

				this.httpBridge.request(endpoint).thenAccept(response -> {
					this.channel(Opendtu.ChannelId.ABSOLUTE_LIMIT).setNextValue(individualPowerLimit);
					if (this.config.debug()) {
						this.log.info("Limit {} successfully set for inverter [{}]  ", individualPowerLimit,
								serialNumber);
					}
				}).exceptionally(ex -> {
					this.log.error("Error setting limit for inverter [{}]: {}", serialNumber, ex.getMessage());
					this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);

					return null;
				});
			});
		});
	}

	private void updateLimitStatusChannels() {
		String statusApiUrl = this.baseUrl + "/api/limit/status";
		this.httpBridge.getJson(statusApiUrl).thenAccept(responseJson -> {
			if (responseJson.isJsonObject()) {
				JsonObject responseObj = responseJson.getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : responseObj.entrySet()) {
					String inverterSerialNumber = entry.getKey(); // Using the serial number to identify the inverter
					JsonObject inverterLimitInfo = entry.getValue().getAsJsonObject();

					int currentLimitRelative = inverterLimitInfo.get("limit_relative").getAsInt();
					int currentLimitAbsolute = inverterLimitInfo.get("max_power").getAsInt();
					String limitAdjustmentStatus = inverterLimitInfo.get("limit_set_status").getAsString();

					// Retrieve inverter data based on its serial number and update its power limit
					// and status
					InverterData inverter = this.inverterDataMap.get(inverterSerialNumber);
					if (inverter != null) {

						this.channel(Opendtu.ChannelId.LIMIT_STATUS).setNextValue(limitAdjustmentStatus);
						this.channel(Opendtu.ChannelId.ABSOLUTE_LIMIT).setNextValue(currentLimitAbsolute);
						this.channel(Opendtu.ChannelId.RELATIVE_LIMIT).setNextValue(currentLimitRelative);

						this.logDebug(this.log,
								"Limit Status: " + limitAdjustmentStatus + " for Inverter: " + inverterSerialNumber);
					} else {
						this.logWarn(this.log,
								"Inverter data not found for serial number [" + inverterSerialNumber + "].");
					}
				}
			}
		}).exceptionally(exception -> {
			this.logError(this.log, "Error fetching inverter status: " + exception.getMessage());
			return null;
		});
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public MeterType getMeterType() {
		return this.meterType;
	}

	@Override
	public SinglePhase getPhase() {
		return this.phase;
	}

	/**
	 * Constructs and returns a ModbusSlaveTable containing the combined Modbus
	 * slave nature tables of multiple components.
	 *
	 * @param accessMode The AccessMode specifying the type of access allowed (READ,
	 *                   WRITE) for the Modbus registers.
	 * @return A new ModbusSlaveTable instance that combines the Modbus nature
	 *         tables of the specified components under the given access mode.
	 */
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(Opendtu.class, accessMode, 100) //
						.build());
	}

}