package io.openems.edge.ess.samsung.ess;

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
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.ess.api.HybridEss;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Ess.Samsung", immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE//
)

@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SamsungEssImpl extends AbstractOpenemsComponent
		implements SamsungEss, SymmetricEss, OpenemsComponent, EventHandler, TimedataProvider, HybridEss {

	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_DISCHARGE_ENERGY);

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	private final Logger log = LoggerFactory.getLogger(SamsungEssImpl.class);

	private String baseUrl;

	private volatile double latestGridPw = 0;
	private volatile double latestPvPw = 0;
	private volatile double latestPcsPw = 0;
	private volatile double latestConsPw = 0;
	private volatile int latestBatteryStatus = -1;
	private volatile int latestGridStatus = -1;
	private volatile int latestSoC = 0;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private BridgeHttpFactory httpBridgeFactory;
	private BridgeHttp httpBridge;

	public SamsungEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				SamsungEss.ChannelId.values(), //
				EssDcCharger.ChannelId.values(), //
				HybridEss.ChannelId.values() //
		//
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.baseUrl = "http://" + config.ip();
		this.httpBridge = this.httpBridgeFactory.get();
		this._setGridMode(GridMode.ON_GRID);

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
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.calculateEnergy();
			break;
		}
	}

	private void fetchAndUpdateEssRealtimeStatus(JsonElement json, Throwable error) {
		if (error != null) {
			this.logError(log, "Error fetching data: " + error.getMessage());
			this._setSlaveCommunicationFailed(true);
			this._setActivePower(0);
			return;
		}
		if (json != null && json.isJsonObject()) {
			JsonObject jsonObj = json.getAsJsonObject().get("ESSRealtimeStatus").getAsJsonObject();
			latestPvPw = jsonObj.get("PvPw").getAsDouble();
			latestPcsPw = jsonObj.get("PcsPw").getAsDouble();
			latestGridPw = jsonObj.get("GridPw").getAsDouble();
			latestConsPw = jsonObj.get("ConsPw").getAsDouble();
			latestBatteryStatus = jsonObj.get("BtStusCd").getAsInt();
			latestConsPw = jsonObj.get("ConsPw").getAsDouble();
			latestGridStatus = jsonObj.get("GridStusCd").getAsInt();
			latestSoC = jsonObj.get("BtSoc").getAsInt();
			
			int BtStusCd = latestBatteryStatus;
			int PcsPw = (int) latestPcsPw;
			int PvPw = (int) latestPvPw;

			// Update the channels
			this.channel(SymmetricEss.ChannelId.SOC).setNextValue(latestSoC);

			switch (BtStusCd) {
			case 0:
				// Battery is in Discharge mode
				this._setDcDischargePower((int) PcsPw);
				break;
			case 1:
				// Battery is in Charge mode
				this._setDcDischargePower((int) -PcsPw);
				break;
			case 2:
				// Battery is in Idle mode
				this._setDcDischargePower(0);
				break;
			default:
				// Handle unknown status codes
				this.logWarn(log, "Unknown Battery Status Code: " + BtStusCd);
				this._setDcDischargePower(0);
				break;
			}
			this._setActivePower((int) (PcsPw - PvPw));
			this._setSlaveCommunicationFailed(false);
		} else {
			this.logError(log, "Invalid JSON response");
			this._setSlaveCommunicationFailed(true);
			this._setActivePower(0);
		}
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() //
				+ "|Surplus:" + this.getSurplusPower() + "W" //
				+ "|" + this.getGridModeChannel().value().asOptionString(); //
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public Integer getSurplusPower() {
		// Adjust the sign of gridPw and pcsPw based on the status codes
		if (latestGridStatus == 1) {
			latestGridPw = -latestGridPw;
		}
		if (latestBatteryStatus == 0) {
			latestPcsPw = -latestPcsPw;
		}

		// Calculate surplus power
		double surplusPower = (latestGridPw + latestPvPw) - (latestPcsPw + latestConsPw);
		// Return the surplus power or 'null' if there is no surplus power
		return surplusPower > 0 ? (int) surplusPower : null;
	}

	private void calculateEnergy() {
		// Calculate AC Energy
		var activePower = this.getActivePowerChannel().getNextValue().get();
		if (activePower == null) {
			// Not available
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
			this.calculateDcChargeEnergy.update(null);
			this.calculateDcDischargeEnergy.update(null);
		} else {
			if (activePower > 0) {
				// Discharge
				this.calculateAcChargeEnergy.update(0);
				this.calculateAcDischargeEnergy.update(activePower);
				this.calculateDcChargeEnergy.update(0);
				this.calculateDcDischargeEnergy.update(activePower);
			} else {
				// Charge
				this.calculateAcChargeEnergy.update(activePower * -1);
				this.calculateAcDischargeEnergy.update(0);
				this.calculateDcChargeEnergy.update(activePower * -1);
				this.calculateDcDischargeEnergy.update(0);
			}
		}

		// Set DC Power explicitly equal to AC Power for clarity
		this._setDcDischargePower(activePower);
	}

}