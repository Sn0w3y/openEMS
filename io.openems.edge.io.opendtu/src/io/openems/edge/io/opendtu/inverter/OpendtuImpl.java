package io.openems.edge.io.opendtu.inverter;

import static io.openems.common.utils.JsonUtils.getAsJsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.http.api.BridgeHttp;
import io.openems.edge.bridge.http.api.BridgeHttpFactory;
import io.openems.edge.bridge.http.api.HttpMethod;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.MeterType;
import io.openems.edge.meter.api.SinglePhase;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})

/*
 * ToDo: - Set overall Power-Limit. I´ve made different Limits for each phase
 * which is useless - Tests on a cloudy day which will cause a lot of deviation
 * while producing power - Add modbus slave functionality - Maybe make
 * inverter-limits dynamic. For example: WR1 600W, WR2 800W. Overall limit
 * 1000W. So individual limits can be set according to output power or better:
 * To a combination of actual production power AND max. output power. In an
 * east/west configuration each inverter can deliver max power
 * 
 */
public class OpendtuImpl extends AbstractOpenemsComponent implements Opendtu, ElectricityMeter, OpenemsComponent,
		EventHandler, TimedataProvider, ManagedSymmetricPvInverter {

	@Reference(policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL //
	)

	private volatile BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;
	private String baseUrl;
	private String encodedAuth;

	@Reference(policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	private final CalculateEnergyFromPower calculateActualEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	private final CalculateEnergyFromPower calculateActualEnergyL1 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L1);

	private final CalculateEnergyFromPower calculateActualEnergyL2 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L2);

	private final CalculateEnergyFromPower calculateActualEnergyL3 = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L3);

	private final Logger log = LoggerFactory.getLogger(OpendtuImpl.class);

	private MeterType meterType = null;
	private SinglePhase phase = null;
	private int numInverters = 0;
	private Boolean isInitialPowerLimitSet = false;

	private List<InverterData> validInverters = new ArrayList<>();
	private Map<String, InverterData> inverterDataMap = new HashMap<>();

	public OpendtuImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), Opendtu.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());

		this.validInverters = InverterData.collectInverterData(config); // Adds Inverter to Array with serial and phase
																		// info
		this.numInverters = validInverters.size();

		this.meterType = config.type();

		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();

		String auth = config.username() + ":" + config.password();
		this.encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

		for (InverterData inverter : validInverters) {
			inverterDataMap.put(inverter.getSerialNumber(), inverter);
			String inverterStatusUrl = "/api/livedata/status?inv=" + inverter.getSerialNumber();
			if (this.isEnabled()) {
				this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + inverterStatusUrl, this::processHttpResult);
			}
		}

		// Set initial limit to each inverter
		// Initial limit is set relative (in percent) to handle different types of
		// inverters
		//
		// To make use of the setLimits-method from other controllers we have to use
		// absolute values (in watts)
		if (!this.isInitialPowerLimitSet) {

			Map<String, String> properties = Map.of("Authorization", "Basic " + this.encodedAuth, "Content-Type",
					"application/x-www-form-urlencoded");
			for (InverterData inverter : validInverters) {
				String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":1, \"limit_value\":%d}",
						inverter.serialNumber, config.initialPowerLimit());
				String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);
				BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config",
						HttpMethod.POST, BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT,
						formattedPayload, properties);

				this.httpBridge.request(endpoint).thenAccept(response -> {
					/*
					 * Initial limits are set relatively in percent ToDo: Read the absolute values
					 * and save them to channels
					 * this.channel(Opendtu.ChannelId.POWER_LIMIT_L1).setNextValue(config.
					 * initialPowerLimit());
					 * this.channel(Opendtu.ChannelId.POWER_LIMIT_L2).setNextValue(config.
					 * initialPowerLimit());
					 * this.channel(Opendtu.ChannelId.POWER_LIMIT_L3).setNextValue(config.
					 * initialPowerLimit());
					 */
					this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(false);
				}).exceptionally(ex -> {
					this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);
					return null;
				});
			}
			this.isInitialPowerLimitSet = true;
		}
	}

	private void processHttpResult(JsonElement result, Throwable error) {
		this._setSlaveCommunicationFailed(result == null);

		Float power = null;
		Float reactivepower = null;
		Float voltage = null;
		Float current = null;
		Integer frequency = null;
		Float totalPower = null; // Power over all inverters connected to the DTU
		String serialNumber = null;
		String phase = null;
		Float powerLimitPerPhaseAbsolute = null;
		Float powerLimitPerPhaseRelative = null;

		if (error != null) {
			this.logDebug(this.log, error.getMessage());
		} else {
			try {
				JsonObject response = JsonUtils.getAsJsonObject(result);
				JsonArray invertersArray = JsonUtils.getAsJsonArray(response, "inverters");

				var totalArray = getAsJsonObject(response, "total");
				JsonObject firstInverter = invertersArray.get(0).getAsJsonObject();

				serialNumber = firstInverter.get("serial").getAsString();

				powerLimitPerPhaseAbsolute = firstInverter.get("limit_absolute").getAsFloat();
				powerLimitPerPhaseRelative = firstInverter.get("limit_relative").getAsFloat();

				JsonObject acData = JsonUtils.getAsJsonObject(firstInverter, "AC");
				JsonObject ac0Data = JsonUtils.getAsJsonObject(acData, "0");

				JsonObject powerObj = JsonUtils.getAsJsonObject(ac0Data, "Power");
				power = JsonUtils.getAsFloat(powerObj, "v");

				JsonObject reactivePowerObj = getAsJsonObject(ac0Data, "ReactivePower");
				reactivepower = JsonUtils.getAsFloat(reactivePowerObj, "v");

				JsonObject voltageObj = JsonUtils.getAsJsonObject(ac0Data, "Voltage");
				voltage = JsonUtils.getAsFloat(voltageObj, "v");

				JsonObject currentObj = JsonUtils.getAsJsonObject(ac0Data, "Current");
				current = JsonUtils.getAsFloat(currentObj, "v");

				JsonObject frequencyObj = JsonUtils.getAsJsonObject(ac0Data, "Frequency");
				frequency = JsonUtils.getAsInt(frequencyObj, "v");

				totalPower = JsonUtils.getAsFloat(getAsJsonObject(totalArray, "Power"), "v"); // in W
			}

			catch (Exception e) {
				this.logDebug(this.log, "Error processing HTTP result: " + e.getMessage());
				this._setSlaveCommunicationFailed(true);
			}
		}

		/*
		 * We don´t need scaling for every value
		 * 
		 * ToDo: clean & refactor
		 */
		int scaledVoltage = Math.round(voltage * 1000);
		int scaledCurrent = Math.round(current * 1000);
		int scaledFrequency = Math.round(frequency * 1000);
		int scaledPower = Math.round(power);
		int scaledTotalPower = Math.round(totalPower);
		int scaledreactivepower = Math.round(reactivepower);
		int powerLimitPerPhaseAbsoluteScaled = Math.round(powerLimitPerPhaseAbsolute);
		int powerLimitPerPhaseRelativeScaled = Math.round(powerLimitPerPhaseRelative);

		if (serialNumber != null) {
			InverterData inverterData = inverterDataMap.get(serialNumber);
			if (inverterData != null) {
				if (power != null) {
					inverterData.setPower(scaledPower);
				}
				if (current != null) {
					inverterData.setCurrent(scaledCurrent);
				}
				if (voltage != null) {
					inverterData.setVoltage(scaledVoltage);
				}
				if (frequency != null) {
					inverterData.setFrequency(scaledFrequency);
				}
				if (powerLimitPerPhaseAbsolute != null) {
					inverterData.setCurrentPowerLimitAbsolute(powerLimitPerPhaseAbsoluteScaled);
				}
				if (powerLimitPerPhaseRelative != null) {
					inverterData.setCurrentPowerLimitRelative(powerLimitPerPhaseRelativeScaled);
				}

				phase = inverterData.getPhase();
			}
		}

		// Feed Channels
		if (phase != null) {
			switch (phase) {
			case "L1":
				this._setActivePowerL1(scaledPower);
				this._setVoltageL1(scaledVoltage);
				this._setCurrentL1(scaledCurrent);
				this._setReactivePowerL1(scaledreactivepower);
				break;
			case "L2":
				this._setActivePowerL2(scaledPower);
				this._setVoltageL2(scaledVoltage);
				this._setCurrentL2(scaledCurrent);
				this._setReactivePowerL2(scaledreactivepower);
				break;
			case "L3":
				this._setActivePowerL3(scaledPower);
				this._setVoltageL3(scaledVoltage);
				this._setCurrentL3(scaledCurrent);
				this._setReactivePowerL3(scaledreactivepower);
				break;
			}
		}

		this._setFrequency(scaledFrequency); // We assume frequency to be equal on all phases/inverters
		this._setActivePower(scaledTotalPower); // ActivePower over the whole cluster
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
		this.calculateEnergy(); // <-- Belongs here and tested working
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

		var actualPowerL1 = this.getActivePowerL1().get();
		if (actualPowerL1 == null) {
			// Not available
			this.calculateActualEnergyL1.update(null);
		} else if (actualPowerL1 > 0) {
			this.calculateActualEnergyL1.update(actualPowerL1);
		} else {
			this.calculateActualEnergyL1.update(0);
		}

		var actualPowerL2 = this.getActivePowerL2().get();
		if (actualPowerL2 == null) {
			// Not available
			this.calculateActualEnergyL2.update(null);
		} else if (actualPowerL2 > 0) {
			this.calculateActualEnergyL2.update(actualPowerL2);
		} else {
			this.calculateActualEnergyL2.update(0);
		}

		var actualPowerL3 = this.getActivePowerL3().get();
		if (actualPowerL3 == null) {
			// Not available
			this.calculateActualEnergyL3.update(null);
		} else if (actualPowerL3 > 0) {
			this.calculateActualEnergyL3.update(actualPowerL3);
		} else {
			this.calculateActualEnergyL3.update(0);
		}

	}

	public void setActivePowerLimit(int powerLimit) throws OpenemsNamedException {

		// first, we use a fix division:
		int individualPowerLimit = Math.round(powerLimit / this.numInverters); // devide the total limit

		inverterDataMap.forEach((serialNumber, inverterData) -> {
			// check if it´s necessary to set a new limit
			if (inverterData.getlimitSetStatus() != null && inverterData.getlimitSetStatus().equals("Pending")) {
				log.info("Still Pending to set limit for serial [{}] ", serialNumber);
				return;
			}
			if ((Math.abs(inverterData.getCurrentPowerLimitAbsolute() - individualPowerLimit)) < 5) {
				log.info("No changes in PowerLimit for serial [{}] ", serialNumber);
				return;
			}
			String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":0, \"limit_value\":%d}",
					serialNumber, (int) individualPowerLimit);

			String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);
			Map<String, String> properties = Map.of("Authorization", "Basic " + this.encodedAuth, "Content-Type",
					"application/x-www-form-urlencoded");

			BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(baseUrl + "/api/limit/config", HttpMethod.POST,
					BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, formattedPayload, properties);

			httpBridge.request(endpoint).thenAccept(response -> {
				log.info("Limit {} successfully set for inverter [{}]  ", individualPowerLimit, serialNumber);
			});
		});
		return;
	}

	private void updateLimitStatusChannels() {
		String statusApiUrl = this.baseUrl + "/api/limit/status";
		this.httpBridge.getJson(statusApiUrl).thenAccept(responseJson -> {
			if (responseJson.isJsonObject()) {
				JsonObject responseObj = responseJson.getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : responseObj.entrySet()) {
					String inverterSerialNumber = entry.getKey(); // Using the serial number to identify the inverter
					JsonObject inverterLimitInfo = entry.getValue().getAsJsonObject();

					int currentLimitPercentage = inverterLimitInfo.get("limit_relative").getAsInt();
					int maximumPowerCapability = inverterLimitInfo.get("max_power").getAsInt();
					String limitAdjustmentStatus = inverterLimitInfo.get("limit_set_status").getAsString();

					// Retrieve inverter data based on its serial number and update its power limits
					// and status
					InverterData inverter = inverterDataMap.get(inverterSerialNumber);
					if (inverter != null) {

						this.channel(Opendtu.ChannelId.LIMIT_STATUS).setNextValue(limitAdjustmentStatus);
						this.channel(Opendtu.ChannelId.MAX_POWER_INVERTER).setNextValue(maximumPowerCapability);
						this.channel(Opendtu.ChannelId.RELATIVE_LIMIT).setNextValue(currentLimitPercentage);

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
}