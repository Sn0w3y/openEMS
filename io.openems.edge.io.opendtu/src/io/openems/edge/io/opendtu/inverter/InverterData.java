package io.openems.edge.io.opendtu.inverter;

import java.util.ArrayList;
import java.util.List;

public class InverterData {
	String serialNumber;
	private String phase;

	private int power;
	private int maxPower;
	private int voltage;
	private int current;
	private int frequency;

	private int currentPowerLimitAbsolute;
	private int currentPowerLimitRelative;
	private String limitSetStatus;

	public InverterData(String serialNumber, String phase) {
		this.serialNumber = serialNumber;
		this.phase = phase;
	}

	public String getSerialNumber() {
		return serialNumber;
	}

	public void setSerialNumber(String serialNumber) {
		this.serialNumber = serialNumber;
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public int getPower() {
		return power;
	}

	public void setPower(int power) {
		this.power = power;
	}

	// max. possible power from inverter
	public int getMaxPower() {
		return maxPower;
	}

	public void setMaxPower(int power) {
		this.maxPower = power; // possibly related to Homer Simpson??
	}

	public int getCurrent() {
		return current;
	}

	public void setCurrent(int current) {
		this.current = current;
	}

	public int getVoltage() {
		return voltage;
	}

	public void setVoltage(int voltage) {
		this.voltage = voltage;
	}

	public int getFrequency() {
		return frequency;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	// current status of limit setting
	public String getlimitSetStatus() {
		return limitSetStatus;
	}

	public void setLimitSetStatus(String limitSetStatus) {
		this.limitSetStatus = limitSetStatus;
	}

	// current power limit in percent
	public int getCurrentPowerLimitRelative() {
		return currentPowerLimitRelative;
	}

	public void setCurrentPowerLimitRelative(int currentPowerLimitRelative) {
		this.currentPowerLimitRelative = currentPowerLimitRelative;
	}

	// current power limit in watts
	public int getCurrentPowerLimitAbsolute() {
		return currentPowerLimitAbsolute;
	}

	public void setCurrentPowerLimitAbsolute(int currentPowerLimitAbsolute) {
		this.currentPowerLimitAbsolute = currentPowerLimitAbsolute;
	}

	static List<InverterData> collectInverterData(Config config) {
		List<InverterData> validInverters = new ArrayList<>();

		if (!config.serialNumberL1().isEmpty()) {
			validInverters.add(new InverterData(config.serialNumberL1(), "L1"));
		}
		if (!config.serialNumberL2().isEmpty()) {
			validInverters.add(new InverterData(config.serialNumberL2(), "L2"));
		}
		if (!config.serialNumberL3().isEmpty()) {
			validInverters.add(new InverterData(config.serialNumberL3(), "L3"));
		}

		return validInverters;
	}

}