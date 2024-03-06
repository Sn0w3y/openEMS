package io.openems.edge.io.opendtu.inverter;

import java.util.ArrayList;
import java.util.List;

public class InverterData {
	private String serialNumber;
	private String phase;

	private int power;
	private int maxPower;
	private int voltage;
	private int current;
	private int frequency;

	private int limitType; // 0 relative; 1 absolute
	private int currentPowerLimitAbsolute;
	private int currentPowerLimitRelative;
	private String limitSetStatus;
	private static int totalCurrentPowerLimitAbsolute = 0;
	private static int totalPower = 0;

	private long lastUpdate;

	public long getLastUpdate() {
		return this.lastUpdate;
	}

	public void setLastUpdate(long lastUpdate) {
		this.lastUpdate = lastUpdate;
	}

	public InverterData(String serialNumber, String phase) {
		this.serialNumber = serialNumber;
		this.phase = phase;
	}

	public String getSerialNumber() {
		return this.serialNumber;
	}

	public void setSerialNumber(String serialNumber) {
		this.serialNumber = serialNumber;
	}

	public String getPhase() {
		return this.phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public int getPower() {
		return this.power;
	}

    public void setPower(int power) {
        synchronized (InverterData.class) {
            totalPower -= this.power;
            totalPower += power;
        }
        this.power = power;
    }

    public static int getTotalPower() {
    	return totalPower;
    }

	public int getMaxPower() {
		return this.maxPower;
	}

	public void setAbsoluteLimit(int power) {
		this.maxPower = power;
	}

	public int getCurrent() {
		return this.current;
	}

	public void setCurrent(int current) {
		this.current = current;
	}

	public int getVoltage() {
		return this.voltage;
	}

	public void setVoltage(int voltage) {
		this.voltage = voltage;
	}

	public int getFrequency() {
		return this.frequency;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	public int getLimitType() {
		return this.limitType;
	}

	public void setLimitType(int limitType) {
		this.limitType = limitType;
	}

	/**
	 * Retrieves the current status of the limit setting.
	 * 
	 * <p>
	 * This method returns a {@code String} that represents the current status of
	 * the limit setting for an inverter. The status indicates whether the limit has
	 * been successfully set, is pending, or has encountered an error.
	 * 
	 * @return A {@code String} representing the current status of the limit
	 *         setting.
	 */
	public String getlimitSetStatus() {
		return this.limitSetStatus;
	}

	public void setLimitSetStatus(String limitSetStatus) {
		this.limitSetStatus = limitSetStatus;
	}

	public int getCurrentPowerLimitRelative() {
		return this.currentPowerLimitRelative;
	}

	public void setPowerLimitRelative(int currentPowerLimitRelative) {
		this.currentPowerLimitRelative = currentPowerLimitRelative;
	}

	public int getCurrentPowerLimitAbsolute() {
		return this.currentPowerLimitAbsolute;
	}

	public void setLimitRelative(int currentPowerLimitAbsolute) {
		// Adjust the total sum when updating the currentPowerLimitAbsolute value
		// Subtract the old value and add the new value to the total
		synchronized (InverterData.class) {
			totalCurrentPowerLimitAbsolute -= this.currentPowerLimitAbsolute;
			totalCurrentPowerLimitAbsolute += currentPowerLimitAbsolute;
		}
		this.currentPowerLimitAbsolute = currentPowerLimitAbsolute;
		// this.lastUpdate = System.currentTimeMillis(); // Update the lastUpdate timestamp

	}

	/**
	 * Static method to get the total sum of CurrentPowerLimitAbsolute across all
	 * instances.
	 * 
	 * @return The total sum of CurrentPowerLimitAbsolute.
	 */
	public static int getTotalCurrentPowerLimitAbsolute() {
		return totalCurrentPowerLimitAbsolute;
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