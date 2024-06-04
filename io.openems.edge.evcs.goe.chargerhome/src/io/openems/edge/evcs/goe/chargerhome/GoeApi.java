package io.openems.edge.evcs.goe.chargerhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.JsonUtils;

public class GoeApi {
	private final String ipAddress;
	private final int executeEveryCycle = 10;
	private int cycle;
	private JsonObject jsonStatus;
	private final EvcsGoeChargerHomeImpl parent;
	public boolean isNewApi;

	public GoeApi(EvcsGoeChargerHomeImpl p) {
		this.ipAddress = p.config.ip();
		this.cycle = 0;
		this.jsonStatus = null;
		this.parent = p;
		this.isNewApi = false;

		// Determine API version
		determineApiVersion();
	}

	private void determineApiVersion() {
		try {
			var url = "http://" + this.ipAddress + "/api/status";
			this.sendRequest(url, "GET");
			this.isNewApi = true;
		} catch (Exception e) {
			this.isNewApi = false;
		}
	}

	/**
	 * Gets the status from go-e. See https://github.com/goecharger
	 *
	 * @return the boolean value
	 * @throws OpenemsNamedException on error
	 */
	public JsonObject getStatus() {
		try {
			// Execute every x-Cycle
			if (this.cycle == 0 || this.cycle % this.executeEveryCycle == 0) {
				var json = new JsonObject();
				var url = this.isNewApi ? "http://" + this.ipAddress + "/api/status"
						: "http://" + this.ipAddress + "/status";
				json = this.sendRequest(url, "GET");

				this.cycle = 1;
				this.jsonStatus = json;
				return json;
			}
			this.cycle++;
			return this.jsonStatus;

		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Sets the activation status for go-e.
	 *
	 * <p>
	 * See https://github.com/goecharger.
	 *
	 * @param active boolean if the charger should be set to active
	 * @return JsonObject with new settings
	 */
	public JsonObject setActive(boolean active) {
		try {
			if (active == this.parent.isActive) {
				return this.jsonStatus;
			}
			var json = new JsonObject();

			Integer status = this.isNewApi ? (active ? 2 : 1) : (active ? 1 : 0);
			var url = this.isNewApi ? "http://" + this.ipAddress + "/api/set?frc=" + status
					: "http://" + this.ipAddress + "/mqtt?payload=alw=" + status;
			var method = this.isNewApi ? "GET" : "PUT";
			json = this.sendRequest(url, method);
			this.parent.isActive = active;
			this.jsonStatus = json;
			return json;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Sets the current in Amperes for go-e charger. See
	 * https://github.com/goecharger.
	 *
	 * @param current current in mA
	 * @return JsonObject with new settings or null if an error occurs
	 */
	public JsonObject setCurrent(int current) {
		Integer currentAmpere = current / 1000; // Convert mA to A
		try {
			// Check if the current needs to be updated and is within the allowable range
			if (currentAmpere.equals(this.parent.activeCurrent / 1000) || currentAmpere < 6 || currentAmpere > 32) {
				return this.jsonStatus; // Return the last known status if no update is needed or value is out of range
			}

			// Construct URL based on API version
			String url = this.isNewApi ? "http://" + this.ipAddress + "/api/set?frc=2&amp=" + currentAmpere // New API
					: "http://" + this.ipAddress + "/mqtt?payload=amp=" + currentAmpere; // Old API endpoint
			String method = this.isNewApi ? "GET" : "PUT"; // Method depends on API version

			// Send the request and update the current status
			JsonObject json = this.sendRequest(url, method);
			if (json != null) {
				this.parent.activeCurrent = currentAmpere * 1000; // Update current in mA
				this.jsonStatus = json; // Store the latest status
			}
			return json;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Sets the Phases for go-e See https://github.com/goecharger.
	 *
	 * @param phases phases command
	 * @return boolean indicating success
	 */
	public boolean setPhases(int phases) {
		try {
			var url = "http://" + this.ipAddress + "/api/set?psm=" + Integer.toString(phases);
			var json = this.sendRequest(url, "GET");
			if (json != null) {
				this.jsonStatus = json;
				return true;
			}
			return false;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Limit MaxEnergy for go-e See https://github.com/goecharger.
	 *
	 * @param limit maximum energy limit enabled
	 * @return JsonObject with new settings
	 */
	public boolean limitMaxEnergy(boolean limit) {
		try {
			var json = new JsonObject();
			var stp = limit ? 2 : 0;
			var url = this.isNewApi ? "http://" + this.ipAddress + "/api/set?stp=" + stp
					: "http://" + this.ipAddress + "/mqtt?payload=stp=" + stp;
			var method = this.isNewApi ? "GET" : "PUT";
			json = this.sendRequest(url, method);
			if (json != null) {
				this.jsonStatus = json;
				return true;
			}
			return false;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Sets the MaxEnergy in 0.1 kWh for go-e See https://github.com/goecharger.
	 *
	 * @param maxEnergy maximum allowed energy
	 * @return JsonObject with new settings
	 */
	public boolean setMaxEnergy(int maxEnergy) {
		try {
			var json = new JsonObject();
			if (maxEnergy > 0) {
				this.limitMaxEnergy(true);
			} else {
				this.limitMaxEnergy(false);
			}
			var url = this.isNewApi ? "http://" + this.ipAddress + "/api/set?dwo=" + maxEnergy
					: "http://" + this.ipAddress + "/mqtt?payload=dwo=" + maxEnergy;
			var method = this.isNewApi ? "GET" : "PUT";
			json = this.sendRequest(url, method);
			if (json != null) {
				this.jsonStatus = json;
				return true;
			}
			return false;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Sends a get or set request to the go-e API.
	 *
	 * @param urlString     used URL
	 * @param requestMethod requested method
	 * @return a JsonObject or JsonArray
	 */
	private JsonObject sendRequest(String urlString, String requestMethod) throws OpenemsNamedException {
		try {
			var url = new URL(urlString);
			var con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod(requestMethod);
			con.setConnectTimeout(5000);
			con.setReadTimeout(5000);
			var status = con.getResponseCode();
			String body;
			try (var in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
				// Read HTTP response
				var content = new StringBuilder();
				String line;
				while ((line = in.readLine()) != null) {
					content.append(line);
					content.append(System.lineSeparator());
				}
				body = content.toString();
			}
			if (status < 300) {
				// Parse response to JSON
				return JsonUtils.parseToJsonObject(body);
			}
			throw new OpenemsException("Error while reading from go-e API. Response code: " + status + ". " + body);
		} catch (OpenemsNamedException | IOException e) {
			throw new OpenemsException(
					"Unable to read from go-e API. " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}
