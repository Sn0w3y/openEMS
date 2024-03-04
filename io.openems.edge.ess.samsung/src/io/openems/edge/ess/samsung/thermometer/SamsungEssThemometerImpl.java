package io.openems.edge.ess.samsung.thermometer;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.edge.bridge.http.api.BridgeHttp;
import io.openems.edge.bridge.http.api.BridgeHttpFactory;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.thermometer.api.Thermometer;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Thermometer.Samsung", immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)

@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class SamsungEssThemometerImpl extends AbstractOpenemsComponent
		implements OpenemsComponent, EventHandler, Thermometer {

	private String baseUrl;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	private final Logger log = LoggerFactory.getLogger(SamsungEssThemometerImpl.class);

	public SamsungEssThemometerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), ManagedSymmetricPvInverter.ChannelId.values(),
				Thermometer.ChannelId.values()//
//
		//
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + "/R3EMSAPP_REAL.ems?file=Weather.json",
				this::fetchAndUpdateEssRealtimeStatus);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.httpBridgeFactory.unget(this.httpBridge);
		this.httpBridge = null;
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}

	}

	private void fetchAndUpdateEssRealtimeStatus(JsonElement json, Throwable error) {
		if (error != null) {
			this.logError(log, "Error fetching data: " + error.getMessage());
			return;
		}

		try {
			if (json != null && json.isJsonObject()) {
				JsonObject jsonObj = json.getAsJsonObject().get("WeatherInfo").getAsJsonObject();

				double temperature = jsonObj.get("Temperature").getAsDouble();
				this._setTemperature((int) (temperature * 10)); // Assuming temperature needs conversion

				int humidity = jsonObj.get("Humidity").getAsInt();
				this.channel(Thermometer.ChannelId.HUMIDITY).setNextValue(humidity);

			}
		} catch (Exception e) {
			this.logError(log, "Failed to process weather data: " + e.getMessage());
		}
	}

	@Override
	public String debugLog() {
		Integer tempValue = this.getTemperature().get(); // tempValue is in deciCelsius (dC)
		float tempCelsius = tempValue / 10.0f; // Convert dC to Celsius
		Integer humidityValue = (Integer) this.channel(Thermometer.ChannelId.HUMIDITY).value().get();
		return String.format("%.1f°C | %d%%", tempCelsius, humidityValue);
	}

}