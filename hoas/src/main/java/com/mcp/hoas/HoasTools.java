package com.mcp.hoas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.mcp.hoas.model.Models.Announcement;
import com.mcp.hoas.model.Models.Machine;
import com.mcp.hoas.model.Models.Reservation;
import com.mcp.hoas.model.Models.ReservationResult;
import com.mcp.hoas.model.Models.Service;
import com.mcp.hoas.model.Models.SlotStatus;
import com.mcp.hoas.model.Models.Timetable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class HoasTools {

	private final HoasBookingService booking;

	public HoasTools(HoasBookingService booking) {
		this.booking = booking;
	}

	@McpTool(name = "list_services",
			description = "List the bookable HOAS services for the logged-in resident (laundry rooms, dryers, saunas) with their service ids and menu group (Pesuvuorot = laundry, Saunavuorot = sauna).",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true))
	public List<Service> listServices() {
		return booking.listServices();
	}

	@McpTool(name = "get_timetable",
			description = "Get one day's timetable for a service: each machine (washing machine, dryer, sauna) with its machineId and hourly slots marked FREE, RESERVED (by someone else), OWN (the user's booking, with reservationId) or OTHER. Also returns the bookable date range and the weekly usage/limit. machineId is null for machines with no free slot that day.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true))
	public Timetable getTimetable(@McpToolParam(description = "Service id from list_services, e.g. 725") int serviceId,
			@McpToolParam(description = "Date as yyyy-MM-dd; defaults to today", required = false) String date,
			@McpToolParam(description = "If true, only return FREE slots", required = false) Boolean onlyFree) {
		Timetable timetable = booking.timetable(serviceId, date == null || date.isBlank() ? null : LocalDate.parse(date));
		if (!Boolean.TRUE.equals(onlyFree)) {
			return timetable;
		}
		List<Machine> machines = timetable.machines()
			.stream()
			.map(m -> new Machine(m.name(), m.machineId(),
					m.slots().stream().filter(s -> s.status() == SlotStatus.FREE).toList()))
			.toList();
		return new Timetable(timetable.serviceId(), timetable.serviceName(), timetable.date(), timetable.minDate(),
				timetable.maxDate(), timetable.weeklyUsage(), machines, timetable.otherServices());
	}

	@McpTool(name = "reserve_slot",
			description = "Reserve a FREE slot (laundry machine, dryer or sauna). Always confirm the exact machine, date and time with the user before calling. Note: cancelling less than 12 hours before the start still counts towards the weekly limit.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
	public ReservationResult reserveSlot(@McpToolParam(description = "machineId from get_timetable, e.g. 913") int machineId,
			@McpToolParam(description = "Date as yyyy-MM-dd") String date,
			@McpToolParam(description = "Slot start time as HH:mm, e.g. 08:00") String time,
			@McpToolParam(description = "Service id the machine belongs to; used to verify the booking. Strongly recommended.", required = false) Integer serviceId) {
		return booking.reserve(machineId, LocalDate.parse(date), LocalTime.parse(time.replace('.', ':')), serviceId);
	}

	@McpTool(name = "list_my_reservations",
			description = "List the user's own upcoming reservations (laundry, dryer, sauna) with reservationId, date, start/end time, machine and service.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true))
	public List<Reservation> listMyReservations() {
		return booking.myReservations();
	}

	@McpTool(name = "cancel_reservation",
			description = "Cancel one of the user's own reservations by reservationId (from list_my_reservations). Always confirm with the user first. Cancelling less than 12 hours before the start still counts towards the weekly limit.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
	public ReservationResult cancelReservation(
			@McpToolParam(description = "reservationId from list_my_reservations or get_timetable") long reservationId) {
		return booking.cancel(reservationId);
	}

	@McpTool(name = "list_announcements",
			description = "List the booking service's announcements (e.g. machines out of order).",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true))
	public List<Announcement> listAnnouncements() {
		return booking.announcements();
	}

}
