package com.mcp.hoas.client;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mcp.hoas.model.Models.Announcement;
import com.mcp.hoas.model.Models.Machine;
import com.mcp.hoas.model.Models.Reservation;
import com.mcp.hoas.model.Models.Service;
import com.mcp.hoas.model.Models.Slot;
import com.mcp.hoas.model.Models.SlotStatus;
import com.mcp.hoas.model.Models.Timetable;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** Turns the HTML pages of the booking site into model objects. */
public final class HoasParser {

	public static final DateTimeFormatter FI_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private static final Pattern TIMETABLE_ID = Pattern.compile("/service/timetable/(\\d+)");

	/** {@code /varaus/service/reserve/{machineId}/{HH.mm}/{yyyy-MM-dd}} */
	public static final Pattern RESERVE_LINK = Pattern
		.compile("/service/reserve/(\\d+)/(\\d{1,2}\\.\\d{2})/(\\d{4}-\\d{1,2}-\\d{1,2})");

	private static final Pattern JS_DATE = Pattern.compile("(minDate|maxDate)\\s*=\\s*'(\\d{2}\\.\\d{2}\\.\\d{4})'");

	private static final Pattern MESSAGE_ID = Pattern.compile("/messages/single/(\\d+)");

	private static final Pattern CANCEL_LINK = Pattern.compile("/service/cancel/(\\d+)");

	/** {@code /service/timetable/724/03/10/2026} */
	private static final Pattern TIMETABLE_DAY = Pattern.compile("/service/timetable/(\\d+)/(\\d{2})/(\\d{2})/(\\d{4})");

	/** "La 03.10.2026 12:00 - 13:00 Kuivausrumpu 2 - Kuivausrummut, Esimerkkikatu 1, A-rappu" */
	private static final Pattern MY_RESERVATION = Pattern
		.compile("^\\S+\\s+(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})\\s+(.+?)\\s+-\\s+(.+)$");

	private HoasParser() {
	}

	/** Top menu groups that point at a timetable, e.g. Pesuvuorot → 724, Saunavuorot → 418. */
	public static List<Service> menuGroups(Document doc) {
		List<Service> groups = new ArrayList<>();
		for (Element link : doc.select("ul.menu a[href]")) {
			Integer id = timetableId(link.attr("href"));
			if (id != null) {
				groups.add(new Service(id, link.text().trim(), link.text().trim()));
			}
		}
		return groups;
	}

	/** Services listed in the service navigation of a timetable page, including the currently selected one. */
	public static List<Service> services(Document doc, int currentServiceId, String group) {
		List<Service> services = new ArrayList<>();
		for (Element el : doc.select(".service-nav span.selected, .service-nav a[href]")) {
			Integer id = el.is("span") ? Integer.valueOf(currentServiceId) : timetableId(el.attr("href"));
			if (id != null) {
				services.add(new Service(id, el.text().trim(), group));
			}
		}
		return services;
	}

	public static Timetable timetable(Document doc, int serviceId) {
		Element title = doc.selectFirst(".srv-name h3");
		String serviceName = title != null ? title.text().trim() : null;

		Element dayLink = doc.selectFirst(".calendar-nav .js-datepicker");
		LocalDate date = dayLink != null ? parseFiDate(dayLink.text().trim()) : null;
		LocalDate minDate = null;
		LocalDate maxDate = null;
		Matcher js = JS_DATE.matcher(doc.select(".calendar-nav script").html());
		while (js.find()) {
			if (js.group(1).equals("minDate")) {
				minDate = parseFiDate(js.group(2));
			}
			else {
				maxDate = parseFiDate(js.group(2));
			}
		}

		List<Machine> machines = new ArrayList<>();
		String weeklyUsage = null;
		Element table = doc.selectFirst("table.calendar");
		if (table != null) {
			Elements rows = table.select("> tbody > tr, > tr");
			List<String> names = new ArrayList<>();
			List<List<Slot>> slotsPerMachine = new ArrayList<>();
			List<Integer> machineIds = new ArrayList<>();
			for (Element row : rows) {
				Elements cells = row.children();
				if (cells.isEmpty()) {
					continue;
				}
				Elements headings = row.select("> td > h3");
				if (!headings.isEmpty() && names.isEmpty()) {
					for (Element h : headings) {
						names.add(h.text().trim());
						slotsPerMachine.add(new ArrayList<>());
						machineIds.add(null);
					}
					continue;
				}
				String first = cells.first().text().trim();
				if (!first.matches("\\d{1,2}[:.]\\d{2}.*")) {
					// e.g. "Vuoroja käytetty tälle viikolle 0 - Viikottainen raja: 10"
					String text = row.text().trim();
					if (!text.isEmpty()) {
						weeklyUsage = text;
					}
					continue;
				}
				for (int i = 1; i < cells.size() && i - 1 < names.size(); i++) {
					Element cell = cells.get(i);
					slotsPerMachine.get(i - 1).add(slot(first, cell));
					Element reserve = cell.selectFirst("a[href*=/service/reserve/]");
					if (reserve != null && machineIds.get(i - 1) == null) {
						Matcher m = RESERVE_LINK.matcher(reserve.attr("href"));
						if (m.find()) {
							machineIds.set(i - 1, Integer.valueOf(m.group(1)));
						}
					}
				}
			}
			for (int i = 0; i < names.size(); i++) {
				machines.add(new Machine(names.get(i), machineIds.get(i), slotsPerMachine.get(i)));
			}
		}

		List<Service> others = services(doc, serviceId, null).stream().filter(s -> s.id() != serviceId).toList();
		return new Timetable(serviceId, serviceName, date, minDate, maxDate, weeklyUsage, machines, others);
	}

	private static Slot slot(String time, Element cell) {
		String text = cell.text().trim();
		Element free = cell.selectFirst("a.free");
		if (free != null) {
			return new Slot(time, free.attr("data-date"), SlotStatus.FREE, null, null);
		}
		Element own = cell.selectFirst("a.myReservation");
		if (own != null) {
			return new Slot(time, own.attr("data-date"), SlotStatus.OWN, cancelId(own.attr("href")), null);
		}
		if (cell.selectFirst(".reserved") != null) {
			return new Slot(time, null, SlotStatus.RESERVED, null, null);
		}
		Element any = cell.children().isEmpty() ? null : cell.child(0);
		String raw = any == null ? text
				: "<%s class=\"%s\" href=\"%s\">%s".formatted(any.tagName(), any.className(), any.attr("href"), text);
		return new Slot(time, null, SlotStatus.OTHER, null, raw);
	}

	public static List<Announcement> announcements(Document doc) {
		List<Announcement> result = new ArrayList<>();
		for (Element link : doc.select("ul.news a.message[href]")) {
			Matcher m = MESSAGE_ID.matcher(link.attr("href"));
			if (m.find()) {
				result.add(new Announcement(Integer.parseInt(m.group(1)), link.text().trim(), link.hasClass("unread")));
			}
		}
		return result;
	}

	/**
	 * The user's own upcoming reservations from the sidebar ({@code ul.myReservations}). The sidebar does not carry
	 * reservation ids; see {@link #ownReservationId}.
	 */
	public static List<Reservation> myReservations(Document doc) {
		List<Reservation> result = new ArrayList<>();
		for (Element link : doc.select("ul.myReservations a[href]")) {
			String text = clean(link.text());
			Matcher m = MY_RESERVATION.matcher(text);
			Matcher day = TIMETABLE_DAY.matcher(link.attr("href"));
			Integer serviceId = day.find() ? Integer.valueOf(day.group(1)) : timetableId(link.attr("href"));
			String kind = link.classNames().stream().filter(c -> !c.equals("korosta")).findFirst().orElse(null);
			if (m.matches()) {
				result.add(new Reservation(null, parseFiDate(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5),
						serviceId, kind));
			}
			else {
				result.add(new Reservation(null, null, null, null, text, null, serviceId, kind));
			}
		}
		return result;
	}

	/** Finds the cancel id of the user's own reservation on a timetable page by start date/time and machine. */
	public static Long ownReservationId(Document doc, LocalDate date, String start, String machine) {
		String prefix = date.format(FI_DATE) + " " + start;
		Long fallback = null;
		for (Element own : doc.select("a.myReservation[href*=/service/cancel/]")) {
			if (!clean(own.attr("data-date")).startsWith(prefix)) {
				continue;
			}
			Long id = cancelId(own.attr("href"));
			if (machine != null && clean(own.attr("data-resource")).endsWith(": " + machine)) {
				return id;
			}
			fallback = fallback == null ? id : fallback;
		}
		return fallback;
	}

	/** Whether the page shows a cancel link for the given reservation. */
	public static boolean offersCancelLink(Document doc, long reservationId) {
		return doc.select("a[href*=/service/cancel/]").stream()
			.anyMatch(a -> Long.valueOf(reservationId).equals(cancelId(a.attr("href"))));
	}

	/** Whether the page still offers the given reserve link, i.e. the slot is still free. */
	public static boolean offersReserveLink(Document doc, int machineId, String time, LocalDate date) {
		for (Element link : doc.select("a[href*=/service/reserve/]")) {
			Matcher m = RESERVE_LINK.matcher(link.attr("href"));
			if (m.find() && Integer.parseInt(m.group(1)) == machineId && m.group(2).equals(time)
					&& LocalDate.parse(normalizeIsoDate(m.group(3))).equals(date)) {
				return true;
			}
		}
		return false;
	}

	/** Visible feedback text on a page (flash messages etc.), outside the sidebar news and the calendar. */
	public static String feedback(Document doc) {
		Elements candidates = doc.select(".error, .success, .notice, .info, .alert, .flash, .message:not(a), p.msg");
		return candidates.isEmpty() ? null : candidates.text().trim();
	}

	private static Long cancelId(String href) {
		Matcher m = CANCEL_LINK.matcher(href);
		return m.find() ? Long.valueOf(m.group(1)) : null;
	}

	/** Normalises the non-breaking spaces and hyphens the site uses in labels. */
	private static String clean(String text) {
		return text.replace('\u00a0', ' ').replace('\u2011', '-').replaceAll("\\s+", " ").trim();
	}

	private static Integer timetableId(String href) {
		Matcher m = TIMETABLE_ID.matcher(href);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	private static LocalDate parseFiDate(String text) {
		try {
			return LocalDate.parse(text, FI_DATE);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static String normalizeIsoDate(String date) {
		String[] p = date.split("-");
		return "%s-%02d-%02d".formatted(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
	}

}
