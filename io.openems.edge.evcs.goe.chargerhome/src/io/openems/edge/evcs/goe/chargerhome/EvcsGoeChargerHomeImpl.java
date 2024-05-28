package io.openems.edge.evcs.goe.chargerhome;

import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;

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
	private Instant lastPhaseChangeTime = Instant.MIN;
	private static final long PHASE_SWITCH_COOLDOWN_SECONDS = 310;

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
			var json = this.goeapi.getStatus();
			if (json == null) {
				this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(true);

			} else {
				try {
					// Is Active
					var alw = JsonUtils.getAsInt(json, "alw");
					if (alw == 1) {
						this.isActive = true;
					} else {
						this.isActive = false;
					}

					// General information
					this.channel(EvcsGoeChargerHome.ChannelId.SERIAL).setNextValue(JsonUtils.getAsString(json, "sse"));
					this.channel(EvcsGoeChargerHome.ChannelId.FIRMWARE)
							.setNextValue(JsonUtils.getAsString(json, "fwv"));

					// Current status
					var status = JsonUtils.getAsInt(json, "car");
					this.channel(EvcsGoeChargerHome.ChannelId.STATUS_GOE).setNextValue(status);
					this.channel(Evcs.ChannelId.STATUS).setNextValue(this.convertGoeStatus(status));

					// Detailed charge information
					// Handle both amp and amx for compatibility
					var amp = JsonUtils.getAsInt(json, "amp");
					var amx = json.has("amx") ? JsonUtils.getAsInt(json, "amx") : amp; // fallback to amp if amx is not
																						// present
					this.activeCurrent = (amp != 0 ? amp : amx) * 1000;
					this.channel(EvcsGoeChargerHome.ChannelId.CURR_USER).setNextValue(this.activeCurrent);

					var nrg = JsonUtils.getAsJsonArray(json, "nrg");
					this.channel(EvcsGoeChargerHome.ChannelId.VOLTAGE_L1).setNextValue(JsonUtils.getAsInt(nrg, 0));
					this.channel(EvcsGoeChargerHome.ChannelId.VOLTAGE_L2).setNextValue(JsonUtils.getAsInt(nrg, 1));
					this.channel(EvcsGoeChargerHome.ChannelId.VOLTAGE_L3).setNextValue(JsonUtils.getAsInt(nrg, 2));
					this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L1)
							.setNextValue(JsonUtils.getAsInt(nrg, 4) * 100);
					this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L2)
							.setNextValue(JsonUtils.getAsInt(nrg, 5) * 100);
					this.channel(EvcsGoeChargerHome.ChannelId.CURRENT_L3)
							.setNextValue(JsonUtils.getAsInt(nrg, 6) * 100);
					var power = JsonUtils.getAsInt(nrg, 11);
					this.channel(EvcsGoeChargerHome.ChannelId.ACTUAL_POWER).setNextValue(power * 10);
					this.channel(Evcs.ChannelId.CHARGE_POWER).setNextValue(power * 10);

					// Hardware limits
					var cableCurrent = JsonUtils.getAsInt(json, "cbl") * 1000;
					this.maxCurrent = cableCurrent > 0 && cableCurrent < this.config.maxHwCurrent() //
							? cableCurrent //
							: this.config.maxHwCurrent();

					this._setFixedMinimumHardwarePower(
							Math.round(this.minCurrent / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());
					this._setFixedMaximumHardwarePower(
							Math.round(this.maxCurrent / 1000f) * DEFAULT_VOLTAGE * Phases.THREE_PHASE.getValue());

					// Phases
					int phases = this.convertGoePhase(JsonUtils.getAsInt(json, "pha"));
					this._setPhases(phases);

					// Energy
					this.channel(EvcsGoeChargerHome.ChannelId.ENERGY_TOTAL)
							.setNextValue(JsonUtils.getAsInt(json, "eto") * 100);
					this.channel(Evcs.ChannelId.ENERGY_SESSION)
							.setNextValue(JsonUtils.getAsInt(json, "dws") * 10 / 3600); // default to 0 if dws not
																						// present

					// Temperature: handle both tma and tmp for compatibility
					var tma = JsonUtils.getAsJsonArray(json, "tma");
					if (tma != null) {
						this.channel(EvcsGoeChargerHome.ChannelId.TEMPERATURE).setNextValue(JsonUtils.getAsFloat(tma));
					} else {
						var tmp = JsonUtils.getAsInt(json, "tmp");
						this.channel(EvcsGoeChargerHome.ChannelId.TEMPERATURE).setNextValue(tmp);
					}

					// Error
					this.channel(EvcsGoeChargerHome.ChannelId.ERROR).setNextValue(JsonUtils.getAsString(json, "err"));
					this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(false);

				} catch (OpenemsNamedException e) {
					this.channel(Evcs.ChannelId.CHARGINGSTATION_COMMUNICATION_FAILED).setNextValue(true);
				}
			}
			break;
		default:
			break;
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
	 * Converts the binary input into the amount of phases that are used to charge.
	 *
	 * @param phase binary phase input
	 * @return amount of phases
	 */
	private int convertGoePhase(int phase) {
		var phasen = (byte) phase & 0b00111000;
		switch (phasen) {
		case 8: // 0b00001000: Phase 1 is active
			return 1;
		case 24: // 0b00011000: Phase 1+2 is active
			return 2;
		case 56: // 0b00111000: Phase1-3 are active
			return 3;
		default:
			return 0;
		}
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
	public boolean applyChargePowerLimit(int power) throws OpenemsException {
		Instant now = Instant.now(this.componentManager.getClock());
		this.log.debug("Applying charge power limit: [Power: " + power + "W] at [" + now + "]");

		boolean isPhaseSwitchCooldownOver = this.lastPhaseChangeTime.plusSeconds(PHASE_SWITCH_COOLDOWN_SECONDS)
				.isBefore(now);
		this.log.debug("Phase switch cooldown over: " + isPhaseSwitchCooldownOver);

		var phasesValue = this.getPhasesAsInt();
		this.log.debug("Current charging phase configuration: " + phasesValue + " phases");

		var current = this.calculateCurrent(power, phasesValue);
		this.log.debug("Calculated current for charging: " + current + "mA");

		// Determine if switching phases is necessary
		final var phases = this.getPhases();
		var prefferedPhases = Phases.preferredPhaseBehavior(power, this.getPhases(), this.config.minHwCurrent(),
				this.config.maxHwCurrent());

		System.out.println("Preferred: " + prefferedPhases);

		boolean phaseSwitchingActive = this.config.phaseSwitch();

		if (phaseSwitchingActive && phases != prefferedPhases && isPhaseSwitchCooldownOver) {
			boolean switchSuccess = this.switchPhases(prefferedPhases, now);
			if (!switchSuccess) {
				this.log.info("Phase switch failed. Exiting.");
				return false; // Early exit if phase switch failed
			}
		}

		// TODO: Remove log. Use epoch time channel for displaying in UI instead
		if (!isPhaseSwitchCooldownOver) {
			Duration timeUntilNextSwitch = Duration.between(now,
					this.lastPhaseChangeTime.plusSeconds(PHASE_SWITCH_COOLDOWN_SECONDS));
			long secondsUntilNextSwitch = timeUntilNextSwitch.getSeconds();
			this.log.info("Phase switch cooldown period has not passed. Time before next switch: "
					+ secondsUntilNextSwitch + " seconds.");
		}

		// TODO: Maybe send previous value during phase switching and wait for it.

		// Send command to set current
		boolean sendSuccess = this.sendChargePowerLimit(current);
		this.log.debug("Command to set current sent. Success: " + sendSuccess);

		return sendSuccess;
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
			this.lastPhaseChangeTime = now; // Update the cooldown timer regardless of the phase switch direction
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
		var current = power * 1000 / phases / 230; // voltage

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

	@Override
	public EvcsPower getEvcsPower() {
		return this.evcsPower;
	}
}
