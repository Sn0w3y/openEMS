package io.openems.edge.io.opendtu.inverter;

import static io.openems.common.utils.JsonUtils.getAsJsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import io.openems.edge.meter.api.SinglePhaseMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class OpendtuImpl extends AbstractOpenemsComponent
		implements Opendtu, SinglePhaseMeter, ElectricityMeter, OpenemsComponent, EventHandler, TimedataProvider {

	@Reference(policy = ReferencePolicy.DYNAMIC, //
			policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL //
	)

	private volatile BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;
	private String baseUrl;

	private volatile Timedata timedata = null;

	private final CalculateEnergyFromPower calculateActualEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	private final Logger log = LoggerFactory.getLogger(OpendtuImpl.class);

	private MeterType meterType = null;
	private SinglePhase phase = null;
	private String serialNumber;
	private Boolean isInitialPowerLimitSet = false;

	public OpendtuImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				Opendtu.ChannelId.values() //
		);

		SinglePhaseMeter.calculateSinglePhaseFromActivePower(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());

		this.meterType = config.type();
		this.phase = config.phase();
		this.serialNumber = config.serialNumber();
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();

		String inverterStatus = "/api/livedata/status?inv=" + config.serialNumber();
		if (this.isEnabled()) {
			this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + inverterStatus, this::processHttpResult);
		}

		if (!this.isInitialPowerLimitSet) {
			String auth = config.username() + ":" + config.password();
			String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
			Map<String, String> properties = Map.of("Authorization", "Basic " + encodedAuth, "Content-Type",
					"application/x-www-form-urlencoded");
			String payloadContent = String.format("{\"serial\":\"%s\", \"limit_type\":1, \"limit_value\":%d}",
					this.serialNumber, config.initialPowerLimit());
			String formattedPayload = "data=" + URLEncoder.encode(payloadContent, StandardCharsets.UTF_8);

			BridgeHttp.Endpoint endpoint = new BridgeHttp.Endpoint(this.baseUrl + "/api/limit/config", HttpMethod.POST,
					BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, formattedPayload, properties);

			this.httpBridge.request(endpoint).thenAccept(response -> {
				this.channel(Opendtu.ChannelId.POWER_LIMIT).setNextValue(config.initialPowerLimit());
				this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(false);
			}).exceptionally(ex -> {
				this.channel(Opendtu.ChannelId.POWER_LIMIT_FAULT).setNextValue(true);
				return null;
			});

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

		if (error != null) {
			this.logDebug(this.log, error.getMessage());
		} else {
			try {
				JsonObject response = JsonUtils.getAsJsonObject(result);

				JsonArray invertersArray = JsonUtils.getAsJsonArray(response, "inverters");
				JsonObject firstInverter = invertersArray.get(0).getAsJsonObject();
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
			}

			catch (Exception e) {
				this.logDebug(this.log, "Error processing HTTP result: " + e.getMessage());
				this._setSlaveCommunicationFailed(true);
			}
		}

		int scaledVoltage = Math.round(voltage * 1000);
		int scaledCurrent = Math.round(current * 1000);
		int scaledFrequency = Math.round(frequency * 1000);
		int scaledPower = Math.round(power);
		int scaledreactivepower = Math.round(reactivepower);

		if (this.phase != null) {
			switch (this.phase) {
			case L1:
				this._setActivePowerL1(scaledPower);
				this._setVoltageL1(scaledVoltage);
				this._setCurrentL1(scaledCurrent);
				this._setVoltageL2(0);
				this._setCurrentL2(0);
				this._setVoltageL3(0);
				this._setCurrentL3(0);
				break;
			case L2:
				this._setVoltageL1(0);
				this._setCurrentL1(0);
				this._setActivePowerL2(scaledPower);
				this._setVoltageL2(scaledVoltage);
				this._setCurrentL2(scaledCurrent);
				this._setVoltageL3(0);
				this._setCurrentL3(0);
				break;
			case L3:
				this._setVoltageL1(0);
				this._setCurrentL1(0);
				this._setVoltageL2(0);
				this._setCurrentL2(0);
				this._setActivePowerL3(scaledPower);
				this._setVoltageL3(scaledVoltage);
				this._setCurrentL3(scaledCurrent);
				break;
			}
		}

		this._setReactivePower(scaledreactivepower);
		this._setCurrent(scaledCurrent);
		this._setVoltage(scaledVoltage);
		this._setFrequency(scaledFrequency);
		this._setActivePower(scaledPower);
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
			this.updateLimitStatusChannel();
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

	private void updateLimitStatusChannel() {
		String url = this.baseUrl + "/api/limit/status";
		this.httpBridge.getJson(url).thenAccept(responseJson -> {
			if (responseJson.isJsonObject() && responseJson.getAsJsonObject().has(this.serialNumber)) {
				JsonObject inverterData = responseJson.getAsJsonObject().getAsJsonObject(this.serialNumber);
				String limitSetStatus = inverterData.get("limit_set_status").getAsString();
				this.channel(Opendtu.ChannelId.LIMIT_STATUS).setNextValue(limitSetStatus);
			} else {
				this.logWarn(log, "Serial number [" + this.serialNumber + "] not found in limit status response.");
			}
		}).exceptionally(ex -> {
			this.logError(log, "Error updating limit status channel: " + ex.getMessage());
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