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

@Designate(ocd = Config.class, //
		factory = true //
)
@Component(immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { "type=PRODUCTION" //

		})
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE })
public class OpendtuImpl extends AbstractOpenemsComponent implements Opendtu, ElectricityMeter, OpenemsComponent,
		EventHandler, TimedataProvider, ManagedSymmetricPvInverter {

	private final Logger log = LoggerFactory.getLogger(OpendtuImpl.class);

	@Reference
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	@Reference(policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL) //
	private volatile Timedata timedata;

	private final CalculateEnergyFromPower calculateActualEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);
	private final CalculateEnergyFromPower calculateActualEnergyL1 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L1);
	private final CalculateEnergyFromPower calculateActualEnergyL2 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L2);
	private final CalculateEnergyFromPower calculateActualEnergyL3 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L3);

	private String baseUrl;
	private String encodedAuth;
	private Config config;

	private Boolean isInitialPowerLimitSet = false;
	private MeterType meterType = null;
	private SinglePhase phase = null;
	private boolean setLimitsAllInverters = true;

	private List<InverterData> validInverters = new ArrayList<>();
	private Map<String, InverterData> inverterDataMap = new HashMap<>();

	public OpendtuImpl() {
		super(OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				Opendtu.ChannelId.values() //
		); //
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.encodedAuth = Base64.getEncoder().encodeToString((config.username() + ":" + config.password()).getBytes());
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();
		this.meterType = config.type();
		this.validInverters = InverterData.collectInverterData(config);
		this.subscribeToLimitStatusUpdates();
		this.subscribeToInverterLiveData();
		this.initializePowerLimits();
	}

	private void processHttpResult(JsonElement result, Throwable error) {
		this._setSlaveCommunicationFailed(result == null);

		Integer power = null;
		Integer reactivepower = null;
		Integer voltage = null;
		Integer current = null;
		Integer frequency = null;
		Integer totalPower = null;
		String serialNumber = null;
		Integer powerLimitPerPhaseAbsolute = null;
		Integer powerLimitPerPhaseRelative = null;
		Integer limitHardware = null;

		if (error != null) {
			this.logDebug(this.log, error.getMessage());
		} else {
			try {
				var response = getAsJsonObject(result);

				var totalObject = getAsJsonObject(response, "total");
				var totalPowerObject = getAsJsonObject(totalObject, "Power");
				totalPower = round(getAsFloat(totalPowerObject, "v"));

				var invertersArray = getAsJsonArray(response, "inverters");
				var inverterResponse = getAsJsonObject(invertersArray.get(0));
				serialNumber = getAsString(inverterResponse, "serial");

				powerLimitPerPhaseAbsolute = round(getAsFloat(inverterResponse, "limit_absolute"));
				powerLimitPerPhaseRelative = round(getAsFloat(inverterResponse, "limit_relative"));

				limitHardware = round(powerLimitPerPhaseAbsolute * (100 / powerLimitPerPhaseRelative));

				this.logDebug(this.log, "Current Limit for [" + serialNumber + "] :" + powerLimitPerPhaseAbsolute
						+ "W / " + powerLimitPerPhaseRelative + "%");

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
		inverterData.setLimitAbsolute(powerLimitPerPhaseAbsolute);
		inverterData.setLimitRelative(powerLimitPerPhaseRelative);
		inverterData.setLimitHardware(limitHardware);
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

		this._setFrequency(frequency);
		this._setActivePower(totalPower);
		this._setSlaveCommunicationFailed(false);
	}

	private void subscribeToLimitStatusUpdates() {
		String limitStatusApiUrl = this.baseUrl + "/api/limit/status";
		this.httpBridge.subscribeJsonEveryCycle(limitStatusApiUrl, this::processLimitStatusUpdate);
	}

	private void subscribeToInverterLiveData() {
		this.validInverters.forEach(inverter -> {
			String inverterStatusUrl = "/api/livedata/status?inv=" + inverter.getSerialNumber();
			if (this.isEnabled()) {
				this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + inverterStatusUrl, this::processHttpResult);
			}
		});
	}

	private void initializePowerLimits() {
		if (!this.isInitialPowerLimitSet && (this.config.absoluteLimit() != -1 || this.config.relativeLimit() != -1)) {
			this.validInverters.forEach(inverter -> this.determineAndSetLimit(this.config, inverter));
			this.isInitialPowerLimitSet = true;
		} else {
			this.logDebug(this.log, "Skipping power limit initialization: both limits are unset (-1).");
		}
	}

	private void determineAndSetLimit(Config config, InverterData inverter) {
		Integer limitValue;
		Integer limitType;

		Map<String, String> properties = Map.of("Authorization", //
				"Basic " + this.encodedAuth, "Content-Type", //
				"application/x-www-form-urlencoded"); //

		if (config.absoluteLimit() == -1 && config.relativeLimit() == -1) {
			return;
		}

		if (config.absoluteLimit() != -1) {
			limitValue = config.absoluteLimit();
			limitType = 0; // Absolute limit type
		} else {
			limitValue = config.relativeLimit();
			limitType = 1; // Relative limit type
		}

		String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":%d, \"limit_value\":%d}",
				inverter.getSerialNumber(), limitType, limitValue);
		String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);

		BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config", HttpMethod.POST,
				BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, formattedPayload, properties);

		this.httpBridge.request(endpoint)
				.thenAccept(response -> this.handlePowerLimitResponse(inverter, limitType, limitValue))
				.exceptionally(ex -> this.handlePowerLimitError(inverter, ex));
	}

	private void processLimitStatusUpdate(JsonElement responseJson, Throwable OpenemsNamedException) {
		this._setSlaveCommunicationFailed(responseJson == null);

		Integer limitRelative = null;
		Integer limitAbsolute = null;
		String limitAdjustmentStatus = null;
		String inverterSerialNumber = null;

		if (OpenemsNamedException != null) {
			this.logDebug(this.log, OpenemsNamedException.getMessage());
		} else {
			try {
				var response = getAsJsonObject(responseJson);

				for (Map.Entry<String, JsonElement> entry : response.entrySet()) {
					inverterSerialNumber = entry.getKey();
					var inverterLimitInfo = getAsJsonObject(entry.getValue());

					limitRelative = round(getAsFloat(inverterLimitInfo, "limit_relative"));
					limitAbsolute = round(getAsFloat(inverterLimitInfo, "max_power"));
					limitAdjustmentStatus = getAsString(inverterLimitInfo, "limit_set_status");

				}
				if (this.setLimitsAllInverters) {
					this.channel(Opendtu.ChannelId.ABSOLUTE_LIMIT).setNextValue(InverterData.getTotalLimitAbsolute());
				}
			} catch (OpenemsNamedException e) {
				this.logDebug(this.log, e.getMessage());
				this._setSlaveCommunicationFailed(true);
				this.setLimitsAllInverters = false;
			}
		}

		InverterData inverter = this.inverterDataMap.get(inverterSerialNumber);
		inverter.setLimitAbsolute(limitAbsolute);
		inverter.setLimitRelative(limitRelative);
		inverter.setLimitStatus(limitAdjustmentStatus);
		this.logDebug(this.log, "Limit Status: " + limitAdjustmentStatus + " for Inverter: " + inverterSerialNumber);

	}

	private void handlePowerLimitResponse(InverterData inverter, int limitType, int limitValue) {
		this.logDebug(this.log, "Power limit successfully set for inverter [" + inverter.getSerialNumber()
				+ "]. LimitType: " + limitType + ", LimitValue: " + limitValue);
		inverter.setLimitType(limitType);
		if (limitType == 0) {
			inverter.setLimitAbsolute(limitValue);
			inverter.setLimitRelative(0);
		} else {
			inverter.setLimitRelative(limitValue);
			inverter.setLimitAbsolute(0);
		}
	}

	private Void handlePowerLimitError(InverterData inverter, Throwable ex) {
		this.logDebug(this.log,
				"Error setting power limit for inverter [" + inverter.getSerialNumber() + "] " + ex.getMessage());
		this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);
		return null;
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
	}

	public void setActivePowerLimit(int powerLimit) throws OpenemsNamedException {
		boolean skipProcessing = this.inverterDataMap.values().stream()
				.anyMatch(inverter -> "Pending".equals(inverter.getLimitStatus()));
		if (skipProcessing || (this.config.absoluteLimit() == -1 && this.config.relativeLimit() == -1)) {
			return;
		}
		long now = System.currentTimeMillis();
		this.inverterDataMap.forEach((serialNumber, inverterData) -> {
			if (this.shouldUpdateInverter(powerLimit, inverterData, now)) {
				this.updateInverterLimit(serialNumber, inverterData, inverterData.getLimitAbsoluteWanted(), now);
			}
		});
	}

	private boolean shouldUpdateInverter(int powerLimit, InverterData inverterData, long now) {
		final long elapsedTimeSinceLastUpdate = now - inverterData.getLastUpdate();
		final long requiredDelay = TimeUnit.SECONDS.toMillis(this.config.delay());
		if (this.calculateNewPowerLimit(powerLimit, inverterData)) {
			int newIndividualLimit = inverterData.getLimitAbsoluteWanted();
			if (Math.abs(newIndividualLimit - inverterData.getLimitAbsolute()) < this.config.threshold()) {
				this.logDebug(this.log, "setActivePowerLimit -> Difference beyond threshold(" + this.config.threshold()
						+ "W) too low. [" + inverterData.getSerialNumber() + "] Wanted Power:" + newIndividualLimit);
				return false;
			}
			return elapsedTimeSinceLastUpdate >= requiredDelay;
		}
		return false;
	}

	private boolean calculateNewPowerLimit(int powerLimit, InverterData inverterData) {
		int totalPower = InverterData.getTotalPower();
		int totalLimitHardware = InverterData.getTotalLimitHardware();
		int powerToDistribute = powerLimit - totalPower;
		int maxLimit = inverterData.getLimitHardware();
		int minLimit = 50;

		if (powerLimit >= totalLimitHardware || powerToDistribute <= 0) {
			inverterData.setLimitAbsoluteWanted(maxLimit);
			this.logDebugInfo(powerLimit, totalPower, maxLimit, 0);
			return true;
		}
		double productionShare = totalPower > 0 ? (double) inverterData.getPower() / totalPower : 0.0;
		int additionalLimit = (int) Math.round(powerToDistribute * productionShare);
		int newLimit = Math.min(maxLimit, Math.max(minLimit, inverterData.getPower() + additionalLimit));

		this.logDebugInfo(powerLimit, totalPower, maxLimit, newLimit);
		inverterData.setLimitAbsoluteWanted(newLimit);
		return true;
	}

	private void logDebugInfo(int powerLimit, int totalPower, int maxLimit, int calculatedLimit) {
		this.logDebug(this.log, String.format(
				"Power limit calculation: Power Limit = %d, Total Power = %d, Hardware Limit = %d, New Limit = %d",
				powerLimit, totalPower, maxLimit, calculatedLimit));
	}

	private void updateInverterLimit(String serialNumber, InverterData inverterData, int newPowerLimit, long now) {
		String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":0, \"limit_value\":%d}", serialNumber,
				newPowerLimit);
		String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);

		Map<String, String> properties = Map.of("Authorization", //
				"Basic " + this.encodedAuth, "Content-Type", //
				"application/x-www-form-urlencoded"); //

		BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config", HttpMethod.POST,
				BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, formattedPayload, properties);

		this.httpBridge.request(endpoint).thenAccept(response -> {
			this.logDebug(this.log, "Limit " + newPowerLimit + " successfully set for inverter [" + serialNumber + "]");
			inverterData.setLastUpdate(now);
			this.setLimitsAllInverters = true;
		}).exceptionally(ex -> {
			this.log.error("Error setting limit for inverter [{}]: {}", serialNumber, ex.getMessage());
			this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);
			this.setLimitsAllInverters = false;
			return null;
		});
	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		var actualPower = this.getActivePower().get();
		if (actualPower == null) {
			this.calculateActualEnergy.update(null);
		} else if (actualPower > 0) {
			this.calculateActualEnergy.update(actualPower);
		} else {
			this.calculateActualEnergy.update(0);
		}

		var actualPowerL1 = this.getActivePowerL1().get();
		if (actualPowerL1 == null) {
			this.calculateActualEnergyL1.update(null);
		} else if (actualPowerL1 > 0) {
			this.calculateActualEnergyL1.update(actualPowerL1);
		} else {
			this.calculateActualEnergyL1.update(0);
		}

		var actualPowerL2 = this.getActivePowerL2().get();
		if (actualPowerL2 == null) {
			this.calculateActualEnergyL2.update(null);
		} else if (actualPowerL2 > 0) {
			this.calculateActualEnergyL2.update(actualPowerL2);
		} else {
			this.calculateActualEnergyL2.update(0);
		}

		var actualPowerL3 = this.getActivePowerL3().get();
		if (actualPowerL3 == null) {
			this.calculateActualEnergyL3.update(null);
		} else if (actualPowerL3 > 0) {
			this.calculateActualEnergyL3.update(actualPowerL3);
		} else {
			this.calculateActualEnergyL3.update(0);
		}

	}

	/**
	 * Constructs and returns a ModbusSlaveTable.
	 * 
	 * @param accessMode The AccessMode specifying the type of access allowed
	 * @return A new ModbusSlaveTable instance that combines the Modbus nature
	 *         tables.
	 */
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(Opendtu.class, accessMode, 100) //
						.build());
	}

	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			super.logInfo(this.log, message);
		}
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
}