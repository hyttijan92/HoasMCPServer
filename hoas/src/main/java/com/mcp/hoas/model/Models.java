package com.mcp.hoas.model;

import java.time.LocalDate;
import java.util.List;

public final class Models {

	private Models() {
	}

	/** A bookable service, e.g. "Pesula, Esimerkkikatu 1, A-rappu" (id 725), within a menu group like "Pesuvuorot". */
	public record Service(int id, String name, String group) {
	}

	public enum SlotStatus {

		/** Free, can be reserved with {@link Machine#machineId()}. */
		FREE,
		/** Reserved by someone else. */
		RESERVED,
		/** The logged-in user's own reservation; can be cancelled with {@link Slot#reservationId()}. */
		OWN,
		/** Anything else the parser does not recognise yet; see {@link Slot#raw()}. */
		OTHER

	}

	/** One time slot of one machine. {@code raw} carries the cell's class/text/link for statuses not yet modelled. */
	public record Slot(String time, String label, SlotStatus status, Long reservationId, String raw) {
	}

	/**
	 * A machine column (washing machine, dryer, sauna). {@code machineId} is the id used for reserving; it is learnt
	 * from the column's reserve links, so it is null when the machine has no free slot that day.
	 */
	public record Machine(String name, Integer machineId, List<Slot> slots) {
	}

	public record Timetable(int serviceId, String serviceName, LocalDate date, LocalDate minDate, LocalDate maxDate,
			String weeklyUsage, List<Machine> machines, List<Service> otherServices) {
	}

	public record Announcement(int id, String title, boolean unread) {
	}

	/**
	 * One of the user's own reservations. {@code reservationId} (used for cancelling) is looked up from the
	 * service's timetable and may be null if it could not be found.
	 */
	public record Reservation(Long reservationId, LocalDate date, String start, String end, String machine,
			String serviceName, Integer serviceId, String kind) {
	}

	public record ReservationResult(boolean success, Boolean verified, Long reservationId, String message) {
	}

}
