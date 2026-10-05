package com.mcp.hoas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcp.hoas.client.HoasClient;
import com.mcp.hoas.client.HoasException;
import com.mcp.hoas.client.HoasParser;
import com.mcp.hoas.model.Models.Announcement;
import com.mcp.hoas.model.Models.Machine;
import com.mcp.hoas.model.Models.Reservation;
import com.mcp.hoas.model.Models.ReservationResult;
import com.mcp.hoas.model.Models.Service;
import com.mcp.hoas.model.Models.Slot;
import com.mcp.hoas.model.Models.SlotStatus;
import com.mcp.hoas.model.Models.Timetable;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public class HoasBookingService {

	private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final DateTimeFormatter RESERVE_TIME = DateTimeFormatter.ofPattern("HH.mm");

	private final HoasClient client;

	public HoasBookingService(HoasClient client) {
		this.client = client;
	}

	public List<Service> listServices() {
		Document home = client.get("/varaus");
		Map<Integer, Service> services = new LinkedHashMap<>();
		for (Service group : HoasParser.menuGroups(home)) {
			Document page = client.get("/varaus/service/timetable/" + group.id());
			List<Service> inGroup = HoasParser.services(page, group.id(), group.name());
			if (inGroup.isEmpty()) {
				inGroup = List.of(group);
			}
			inGroup.forEach(s -> services.putIfAbsent(s.id(), s));
		}
		return new ArrayList<>(services.values());
	}

	public Timetable timetable(int serviceId, LocalDate date) {
		String path = date != null ? timetablePath(serviceId, date) : "/varaus/service/timetable/" + serviceId;
		return HoasParser.timetable(client.get(path), serviceId);
	}

	public List<Announcement> announcements() {
		return HoasParser.announcements(client.get("/varaus"));
	}

	/**
	 * The user's upcoming reservations. Reservation ids are looked up from each reservation's timetable page (one
	 * request per distinct service and day).
	 */
	public List<Reservation> myReservations() {
		List<Reservation> listed = HoasParser.myReservations(client.get("/varaus"));
		Map<String, Document> pages = new LinkedHashMap<>();
		List<Reservation> result = new ArrayList<>();
		for (Reservation r : listed) {
			Long id = null;
			if (r.serviceId() != null && r.date() != null) {
				Document page = pages.computeIfAbsent(r.serviceId() + "/" + r.date(),
						key -> client.get(timetablePath(r.serviceId(), r.date())));
				id = HoasParser.ownReservationId(page, r.date(), r.start(), r.machine());
			}
			result.add(new Reservation(id, r.date(), r.start(), r.end(), r.machine(), r.serviceName(), r.serviceId(),
					r.kind()));
		}
		return result;
	}

	/**
	 * Cancels one of the user's own reservations. Only ids found among the user's reservations are accepted; the
	 * result is verified by checking that the cancel link has disappeared from the timetable.
	 */
	public ReservationResult cancel(long reservationId) {
		Reservation target = myReservations().stream()
			.filter(r -> Long.valueOf(reservationId).equals(r.reservationId()))
			.findFirst()
			.orElseThrow(() -> new HoasException(
					"Reservation " + reservationId + " is not among your upcoming reservations (see list_my_reservations)"));

		Document response = client.get("/varaus/service/cancel/" + reservationId);
		String feedback = HoasParser.feedback(response);
		Document check = client.get(timetablePath(target.serviceId(), target.date()));
		boolean stillThere = HoasParser.offersCancelLink(check, reservationId);
		String what = target.machine() + " on " + target.date() + " at " + target.start();
		String message = stillThere ? "Reservation " + reservationId + " (" + what + ") is still there: cancelling FAILED."
				: "Cancelled " + what + ".";
		return new ReservationResult(!stillThere, true, reservationId,
				message + (feedback != null ? " Site said: " + feedback : ""));
	}

	/**
	 * Reserves a slot. The site gives no explicit success response, so the result is verified on the timetable
	 * (the response page or, when {@code serviceId} is given, a fresh timetable): the slot should now be the user's
	 * own reservation, or at least no longer offered as free.
	 */
	public ReservationResult reserve(int machineId, LocalDate date, LocalTime time, Integer serviceId) {
		String slotTime = time.format(RESERVE_TIME);
		if (serviceId != null) {
			Timetable before = timetable(serviceId, date);
			if (before.date() != null && !before.date().equals(date)) {
				throw new HoasException("Date " + date + " is not bookable for service " + serviceId
						+ " (bookable range " + before.minDate() + " – " + before.maxDate() + ")");
			}
		}

		Document response = client.get("/varaus/service/reserve/" + machineId + "/" + slotTime + "/" + date);
		String feedback = HoasParser.feedback(response);
		String siteSaid = feedback != null ? " Site said: " + feedback : "";

		Document check = serviceId != null ? client.get(timetablePath(serviceId, date)) : response;
		Timetable after = check.selectFirst("table.calendar") != null
				? HoasParser.timetable(check, serviceId != null ? serviceId : 0) : null;
		if (after == null || !date.equals(after.date())) {
			return new ReservationResult(true, false, null,
					"Reservation request sent, but it could not be verified (pass serviceId to verify)." + siteSaid);
		}

		String hhmm = time.format(DateTimeFormatter.ofPattern("HH:mm"));
		Slot slot = after.machines()
			.stream()
			.filter(m -> Integer.valueOf(machineId).equals(m.machineId()))
			.flatMap(m -> m.slots().stream())
			.filter(s -> s.time().equals(hhmm))
			.findFirst()
			.orElse(null);
		if (slot != null && slot.status() == SlotStatus.OWN) {
			return new ReservationResult(true, true, slot.reservationId(),
					"Reserved machine " + machineId + " on " + date + " at " + hhmm + "." + siteSaid);
		}
		if (HoasParser.offersReserveLink(check, machineId, slotTime, date)) {
			return new ReservationResult(false, true, null,
					"Slot is still free after the request: reservation was NOT made (weekly limit reached?)." + siteSaid);
		}
		Long ownId = HoasParser.ownReservationId(check, date, hhmm, machineName(after, machineId));
		boolean own = ownId != null;
		return new ReservationResult(own, own, ownId, own ? "Reserved machine " + machineId + " on " + date + " at " + hhmm + "." + siteSaid
				: "Slot is no longer free but is not shown as your reservation; someone else may have taken it." + siteSaid);
	}

	private static String machineName(Timetable timetable, int machineId) {
		return timetable.machines()
			.stream()
			.filter(m -> Integer.valueOf(machineId).equals(m.machineId()))
			.map(Machine::name)
			.findFirst()
			.orElse(null);
	}

	private static String timetablePath(int serviceId, LocalDate date) {
		return "/varaus/service/timetable/" + serviceId + "/" + date.format(URL_DATE);
	}

}
