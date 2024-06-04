package io.openems.edge.evcs.goe.chargerhome;

import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Optional;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.evcs.api.AbstractManagedEvcsComponent;
import io.openems.edge.evcs.api.ChargingType;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.EvcsPower;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.Phases;
import io.openems.edge.evcs.api.Status;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evcs.Goe.ChargerHome", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
})
public class EvcsGoeChargerHomeImpl extends AbstractManagedEvcsComponent
		implements EvcsGoeChargerHome, ManagedEvcs, Evcs, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(EvcsGoeChargerHomeImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private EvcsPower evcsPower;

	/** Is charger active. */
	protected boolean isActive;
	/** Actual current. */
	protected int activeCurrent;

	protected Config config;

	private GoeApi goeapi = null;
	/** Minimal current. */
	private int minCurrent;
	/** Maximum current. */
	private int maxCurrent;

	public EvcsGoeChargerHomeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ManagedEvcs.ChannelId.values(), //
				Evcs.ChannelId.values(), //
				EvcsGoeChargerHome.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws UnknownHostException {
		super.activate(context, config.id(), config.alias(), config.enabled());

		this.channel(EvcsGoeChargerHome.ChannelId.ALIAS).setNextValue(config.alias());
		this.config = config;
		this.minCurrent = config.minHwCurrent();
		this.maxCurrent = config.maxHwCurrent();
		this._setChargingType(ChargingType.AC);
		this._setPowerPrecision(230);

		// start api-Worker
		this.goeapi = new GoeApi(this);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		super.handleEvent(event);
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:

			// handle writes
			var json = this.goeapi.getStatus(); // Retrieve charger status from API.
			if (json == null) {
				this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(true); // Log
																										// communication
																										// failure.
				return;
			}

			try {
				// Determine active status of the charger.
				this.isActive = JsonUtils.getAsBoolean(json, "alw");

				// Retrieve and update general information.
				this.channel(EvcsGoeChargerHome.ChannelId.SERIAL).setNextValue(JsonUtils.getAsString(json, "sse"));
				this.channel(EvcsGoeChargerHome.ChannelId.FIRMWARE).setNextValue(JsonUtils.getAsString(json, "fwv"));

				// Update current charger status.
				int status = JsonUtils.getAsInt(json, "car");
				this.channel(EvcsGoeChargerHome.ChannelId.STATUS_GOE).setNextValue(status);
				this.channel(Evcs.ChannelId.STATUS).setNextValue(this.convertGoeStatus(status));

				// Handle detailed charge information for compatibility.
				int amp = JsonUtils.getAsInt(json, "amp");
				int amx = json.has("amx") ? JsonUtils.getAsInt(json, "amx") : amp;
				this.activeCurrent = (amp != 0 ? amp : amx) * 1000;
				this.channel(EvcsGoeChargerHome.ChannelId.CURR_USER).setNextValue(this.activeCurrent);

				// Adjust currents and power based on API version.
				var nrg = JsonUtils.getAsJsonArray(json, "nrg");
				int factor = this.goeapi.isNewApi ? 1000 : 100; // Adjust factor for current and power based on API
																// version.
				this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L1).setNextValue(JsonUtils.getAsInt(nrg, 4) * factor);
				this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L2).setNextValue(JsonUtils.getAsInt(nrg, 5) * factor);
				this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L3).setNextValue(JsonUtils.getAsInt(nrg, 6) * factor);

				int power = JsonUtils.getAsInt(nrg, 11);
				if (!this.goeapi.isNewApi) { // Apply old API factor if applicable.
					power *= 10;
				}
				this.channel(EvcsGoeChargerHome.ChannelId.ACTUAL_POWER).setNextValue(power);
				this.channel(Evcs.ChannelId.CHARGE_POWER).setNextValue(power);

				// Set hardware limits based on cable current.
				int cableCurrent = JsonUtils.getAsOptionalInt(json, "cbl").orElse(0) * 1000;
				this.maxCurrent = Math.min(cableCurrent, this.config.maxHwCurrent());
				this._setFixedMinimumHardwarePower(
						Math.round(this.minCurrent / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());
				this._setFixedMaximumHardwarePower(
						Math.round(this.maxCurrent / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());

				// Set the number of phases used based on the API.
				int phases = this.goeapi.isNewApi
						? convertGoePhase(Optional.ofNullable(JsonUtils.getAsJsonArray(json, "pha")))
						: convertGoePhase(JsonUtils.getAsInt(json, "binaryPhaseKey"));
				this._setPhases(phases);

				// Update total and session energy.
				int totalEnergy = JsonUtils.getAsInt(json, "eto");
				if (!this.goeapi.isNewApi) { // Apply old API factor if applicable.
					totalEnergy *= 10;
				}
				this.channel(EvcsGoeChargerHome.ChannelId.ENERGY_TOTAL).setNextValue(totalEnergy);

				var energySession = json.has("dws") ? JsonUtils.getAsInt(json, "dws") * 10 / 3600
						: JsonUtils.getAsInt(json, "wh");
				this.channel(Evcs.ChannelId.ENERGY_SESSION).setNextValue(energySession);

				// Clear error status.
				this.channel(EvcsGoeChargerHome.ChannelId.ERROR).setNextValue(JsonUtils.getAsInt(json, "err"));
				this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(false);

			} catch (OpenemsNamedException e) {
				// Handle exception by logging failure.
				this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(true);
			}
		}
	}

	private Status convertGoeStatus(int status) {
		switch (status) {
		case 1: // ready for charging, car unplugged
			return Status.NOT_READY_FOR_CHARGING;
		case 2: // charging
			return Status.CHARGING;
		case 3: // waiting for car
			return Status.READY_FOR_CHARGING;
		case 4: // charging finished, car plugged
			return Status.CHARGING_FINISHED;
		default:
			return Status.UNDEFINED;
		}
	}

	/**
	 * Converts binary phase input into the number of active phases.
	 * 
	 * @param phase the binary phase input as an integer
	 * @return the number of active phases
	 */
	private int convertGoePhase(int phase) {
		int phasen = phase & 0b00111000; // Mask to focus on relevant bits
		switch (phasen) {
		case 0b00001000: // Phase 1 is active
			return 1;
		case 0b00011000: // Phase 1 and 2 are active
			return 2;
		case 0b00111000: // Phase 1, 2, and 3 are active
			return 3;
		default:
			return 0; // No active phases detected
		}
	}

	/**
	 * Converts a JSON array indicating phase activity into the number of active
	 * phases.
	 * 
	 * @param phase JSON array where each boolean entry indicates if a phase is
	 *              active
	 * @return the number of active phases
	 */
	private int convertGoePhase(Optional<JsonArray> phase) {
		if (!phase.isPresent()) {
			return 0; // Return 0 if the JSON array is not present
		}

		JsonArray phaseArray = phase.get();
		int activePhases = 0;
		for (int i = 0; i < 3; i++) { // Assume the array has three elements
			if (phaseArray.get(i).getAsBoolean()) {
				activePhases++; // Count each active phase
			}
		}
		return activePhases;
	}

	@Override
	public boolean applyChargePowerLimit(int power) throws OpenemsException {
		Instant now = Instant.now(this.componentManager.getClock());
		this.log.debug("Applying charge power limit: [Power: " + power + "W] at [" + now + "]");

		int maxPowerCurrentSetting = getConfiguredMaximumHardwarePower(); // Get the max power with full phase capacity
		var phasesValue = this.getPhasesAsInt();
		this.log.debug("Current charging phase configuration: " + phasesValue + " phases");

		if (power > maxPowerCurrentSetting && this.config.phaseSwitch()) {
			// Try to switch to a higher phase setting if not already at maximum capacity
			var preferredPhases = Phases.THREE_PHASE; // Assuming three phases is the max configuration
			boolean switchSuccess = switchPhases(preferredPhases, now);
			if (!switchSuccess) {
				this.log.warn("Failed to switch phases to accommodate power demand.");
				return false; // If phase switch failed, return early.
			}
		}

		var current = calculateCurrent(power, this.getPhasesAsInt()); // Recalculate current with possibly new phase
																		// setting
		this.log.debug("Calculated current for charging: " + current + "mA");

		// Send command to set current
		return sendChargePowerLimit(current);
	}

	private int calculateCurrent(int power, int phases) {
		// Calculation for PV surplus charging from 1380W to 11kW or 22kW
		int current = power / (230 * phases);
		if (current < 6) {
			current = 6; // Minimum 6A
		} else if (current > 32) {
			current = 32; // Maximum 32A for 3 phases
		}
		return current * 1000; // Convert to mA
	}

	private boolean switchPhases(Phases prefferedPhases, Instant now) {
		int command;
		switch (prefferedPhases) {
		case ONE_PHASE:
			command = 1;
			break;
		case THREE_PHASE:
			command = 2;
			break;
		default:
			command = 0;
			break;
		}

		if (this.goeapi.setPhases(command)) {
			this.log.info(
					"Switched to " + (prefferedPhases == Phases.ONE_PHASE ? "1 phase" : "3 phases") + " successfully.");
			return true;
		} else {
			this.log.warn(
					"Failed to switch to " + (prefferedPhases == Phases.ONE_PHASE ? "1 phase" : "3 phases") + ".");
			return false;
		}
	}

	private boolean sendChargePowerLimit(int power) {
		var phases = this.getPhasesAsInt();
		var current = power * 1000 / phases /* e.g. 3 phases */ / 230; /* voltage */

		var result = this.goeapi.setCurrent(current);
		if (result.isJsonObject()) {
			this._setSetChargePowerLimit(power);
			this.debugLog(result.toString());
			return true;
		}
		return false;
	}

	@Override
	public boolean applyDisplayText(String text) throws OpenemsException {
		return false;
	}

	@Override
	public boolean pauseChargeProcess() throws OpenemsException {
		this.goeapi.setActive(false);
		return this.sendChargePowerLimit(0);
	}

	@Override
	public int getMinimumTimeTillChargingLimitTaken() {
		return 30;
	}

	@Override
	public int getConfiguredMinimumHardwarePower() {
		return Math.round(this.config.minHwCurrent() / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue();
	}

	@Override
	public int getConfiguredMaximumHardwarePower() {
		return Math.round(this.config.maxHwCurrent() / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue();
	}

	/**
	 * Debug Log.
	 * 
	 * <p>
	 * Logging only if the debug mode is enabled
	 * 
	 * @param message text that should be logged
	 */
	public void debugLog(String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public boolean getConfiguredDebugMode() {
		return this.config.debugMode();
	}

	@Override
	public EvcsPower getEvcsPower() {
		return this.evcsPower;
	}
}
