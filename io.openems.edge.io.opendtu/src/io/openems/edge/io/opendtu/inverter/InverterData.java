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

	/**
	 * Retrieves the serial number of the inverter.
	 * 
	 * @return The serial number as a {@code String}.
	 */
	public String getSerialNumber() {
		return this.serialNumber;
	}

	/**
	 * Sets the serial number for the inverter.
	 * 
	 * @param serialNumber The new serial number as a {@code String}.
	 */
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

	public void setMaxPower(int power) {
		this.maxPower = power; // possibly related to Homer Simpson??
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

	/**
	 * Retrieves the current power limit set for the inverter in percent.
	 * 
	 * <p>
	 * Returns the power limit currently set for the inverter as a percentage of its
	 * maximum capacity.
	 * 
	 * @return An {@code int} value representing the current power limit set for the
	 *         inverter, in percent.
	 */
	public int getCurrentPowerLimitRelative() {
		return this.currentPowerLimitRelative;
	}

	public void setCurrentPowerLimitRelative(int currentPowerLimitRelative) {
		this.currentPowerLimitRelative = currentPowerLimitRelative;
	}

	public int getCurrentPowerLimitAbsolute() {
		return this.currentPowerLimitAbsolute;
	}

	public void setCurrentPowerLimitAbsolute(int currentPowerLimitAbsolute) {
		// Adjust the total sum when updating the currentPowerLimitAbsolute value
		// Subtract the old value and add the new value to the total
		synchronized (InverterData.class) {
			totalCurrentPowerLimitAbsolute -= this.currentPowerLimitAbsolute;
			totalCurrentPowerLimitAbsolute += currentPowerLimitAbsolute;
		}
		this.currentPowerLimitAbsolute = currentPowerLimitAbsolute;
		// this.lastUpdate = System.currentTimeMillis(); // Update the lastUpdate
		// timestamp

	}
	
	public void setPowerLimits(Integer limitType, Integer limitValue) {
	    this.setLimitType(limitType);
	    if (limitType == 0) { // Absolute limit type
	        this.setCurrentPowerLimitAbsolute(limitValue);
	        this.setCurrentPowerLimitRelative(0); // Indicate that the relative limit is not used
	    } else { // Relative limit type
	        this.setCurrentPowerLimitRelative(limitValue);
	        this.setCurrentPowerLimitAbsolute(0); // Indicate that the absolute limit is not used
	    }
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