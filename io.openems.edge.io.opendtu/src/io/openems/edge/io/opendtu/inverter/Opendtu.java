package io.openems.edge.io.opendtu.inverter;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.SinglePhaseMeter;

public interface Opendtu extends SinglePhaseMeter, ElectricityMeter, OpenemsComponent, EventHandler {

	/**
	 * Channel for setting the Power Limit.
	 */
	public default WriteChannel<Integer> setPowerLimit() {
		return this.channel(ChannelId.RELATIVE_LIMIT);
	}

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Slave Communication Failed Fault.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: State
		 * </ul>
		 */
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT)), //

		/**
		 * Maximum Ever Actual Power.
		 *
		 * <ul>
		 * <li>Interface: Ess DC Charger
		 * <li>Type: Integer
		 * <li>Unit: W
		 * <li>Range: positive or '0'
		 * <li>Implementation Note: value is automatically derived from ACTUAL_POWER
		 * </ul>
		 */
		MAX_ACTUAL_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //

		/**
		 * The Limit Status as String of the Power Limit Response.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: String
		 * <li>Expected values: "Ok", "Pending", "Failure"
		 * </ul>
		 */
		LIMIT_STATUS(Doc.of(OpenemsType.STRING)//
				.text("Limit Status")), //
		/**
		 * The maximum Power of an Inverter.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: Integer
		 * </ul>
		 */
		MAX_POWER_INVERTER(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_ONLY)),
		/**
		 * The relative Limit Power set to an Inverter.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: Integer
		 * <li>Unit: %
		 * </ul>
		 */
		RELATIVE_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.PERCENT) //
				.accessMode(AccessMode.READ_WRITE)),
		/**
		 * The absolute Limit Power set to an Inverter.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		ABSOLUTE_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)),

		/**
		 * Power Limit Setting Failed Fault.
		 *
		 * <ul>
		 * <li>Interface: Opendtu
		 * <li>Type: State
		 * </ul>
		 */
		POWER_LIMIT_FAULT(Doc.of(Level.FAULT)); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#SLAVE_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getSlaveCommunicationFailedChannel() {
		return this.channel(ChannelId.SLAVE_COMMUNICATION_FAILED);
	}

	/**
	 * Gets the Slave Communication Failed State. See
	 * {@link ChannelId#SLAVE_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getSlaveCommunicationFailed() {
		return this.getSlaveCommunicationFailedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SLAVE_COMMUNICATION_FAILED} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSlaveCommunicationFailed(boolean value) {
		this.getSlaveCommunicationFailedChannel().setNextValue(value);
	}
}
