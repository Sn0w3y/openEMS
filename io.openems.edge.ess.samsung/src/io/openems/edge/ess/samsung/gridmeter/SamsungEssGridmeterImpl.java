package io.openems.edge.ess.samsung.gridmeter;

import org.osgi.service.cm.ConfigurationAdmin;
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

import io.openems.edge.bridge.http.api.BridgeHttp;
import io.openems.edge.bridge.http.api.BridgeHttpFactory;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.MeterType;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Grid-Meter.Samsung", immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)

@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class SamsungEssGridmeterImpl extends AbstractOpenemsComponent
		implements SamsungEssGridmeter, ElectricityMeter, OpenemsComponent, EventHandler, TimedataProvider {

	@Reference
	protected ConfigurationAdmin cm;

	private String baseUrl;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	private String currentGridStatus = "Unknown";

	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);
	private final CalculateEnergyFromPower calculateConsumptionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY);

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	private final Logger log = LoggerFactory.getLogger(SamsungEssGridmeterImpl.class);

	private Config config;

	public SamsungEssGridmeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();
		this.config = config;

		if (!this.isEnabled()) {
			return;
		}

		this.httpBridge.subscribeJsonEveryCycle(this.baseUrl + "/R3EMSAPP_REAL.ems?file=ESSRealtimeStatus.json",
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

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.calculateEnergy(); // Call the calculateEnergy method here
			break;
		}
	}

	private void fetchAndUpdateEssRealtimeStatus(JsonElement json, Throwable error) {
		if (error != null) {
			this.logError(log, "Error fetching data: " + error.getMessage());
			return;
		}

		try {
			JsonObject jsonObj = json.getAsJsonObject().get("ESSRealtimeStatus").getAsJsonObject();
			double gridPw = jsonObj.has("GridPw") ? jsonObj.get("GridPw").getAsDouble() * 1000 : 0; // Multiply by 1000
																									// if needed
			int GridStusCd = jsonObj.has("GridStusCd") ? jsonObj.get("GridStusCd").getAsInt() : -1;

			switch (GridStusCd) {
			case 0:
				// Buy from Grid is positive
				this.currentGridStatus = "Buy from Grid";
				break;

			case 1:
				// Sell to Grid is negative
				gridPw = -gridPw;
				this.currentGridStatus = "Sell to Grid";
				break;

			default:
				// Handle unknown status codes if needed
				this.currentGridStatus = "Unknown";
				gridPw = 0;
				this.logWarn(log, "Unknown Grid Status Code: " + GridStusCd);
			}

			this._setActivePowerL1((int) (gridPw / 3));
			this._setActivePowerL2((int) (gridPw / 3));
			this._setActivePowerL3((int) (gridPw / 3));

			this._setVoltageL1(230000); // Assuming you want to set voltage in millivolts
			this._setVoltageL2(230000); // Assuming you want to set voltage in millivolts
			this._setVoltageL3(230000); // Assuming you want to set voltage in millivolts

			// Corrected current calculation
			this._setCurrentL1((int) ((gridPw / 3) / 230.0 * 1000)); // Power in W, Voltage in V, Current in mA
			this._setCurrentL2((int) ((gridPw / 3) / 230.0 * 1000)); // Repeat calculation for L2
			this._setCurrentL3((int) ((gridPw / 3) / 230.0 * 1000)); // Repeat calculation for L3


			this._setActivePower((int) gridPw);
		} catch (Exception e) {
			this.logError(log, "Failed to process Grid Meter Real-time Status: " + e.getMessage());
		}
	}

	private void calculateEnergy() {
		Integer activePower = this.getActivePower().orElse(null);
		if (activePower == null) {
			// Not available
			this.calculateProductionEnergy.update(null);
			this.calculateConsumptionEnergy.update(null);
		} else if (activePower > 0) {
			// Buy-From-Grid
			this.calculateProductionEnergy.update(activePower);
			this.calculateConsumptionEnergy.update(0);
		} else {
			// Sell-To-Grid
			this.calculateProductionEnergy.update(0);
			this.calculateConsumptionEnergy.update(-activePower);
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString() + "|Status: " + this.currentGridStatus;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public MeterType getMeterType() {
		return this.config.type();
	}

}