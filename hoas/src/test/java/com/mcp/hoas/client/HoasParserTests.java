package com.mcp.hoas.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;

import com.mcp.hoas.model.Models.Machine;
import com.mcp.hoas.model.Models.Service;
import com.mcp.hoas.model.Models.Slot;
import com.mcp.hoas.model.Models.SlotStatus;
import com.mcp.hoas.model.Models.Timetable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HoasParserTests {

	private Document doc;

	@BeforeEach
	void load() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/timetable-724.html")) {
			doc = Jsoup.parse(in, "UTF-8", "https://booking-hoas.tampuuri.fi/varaus/service/timetable/724/03/10/2026");
		}
	}

	@Test
	void parsesMenuGroupsAndServices() {
		assertThat(HoasParser.menuGroups(doc)).extracting(Service::id, Service::name)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(724, "Pesuvuorot"),
					org.assertj.core.groups.Tuple.tuple(418, "Saunavuorot"));
		assertThat(HoasParser.services(doc, 724, "Pesuvuorot")).extracting(Service::id).containsExactly(724, 725);
	}

	@Test
	void parsesTimetable() {
		Timetable t = HoasParser.timetable(doc, 724);
		assertThat(t.serviceName()).isEqualTo("Kuivausrummut, Esimerkkikatu 1, A-rappu");
		assertThat(t.date()).isEqualTo(LocalDate.of(2026, 10, 3));
		assertThat(t.minDate()).isEqualTo(LocalDate.of(2026, 10, 3));
		assertThat(t.maxDate()).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(t.weeklyUsage()).contains("Viikottainen raja: 10");
		assertThat(t.otherServices()).extracting(Service::id).containsExactly(725);

		assertThat(t.machines()).extracting(Machine::name, Machine::machineId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("Kuivausrumpu 1", 913),
					org.assertj.core.groups.Tuple.tuple("Kuivausrumpu 2", 914));
		Machine first = t.machines().get(0);
		assertThat(first.slots()).hasSize(4);
		assertThat(first.slots().get(0).status()).isEqualTo(SlotStatus.FREE);
		assertThat(first.slots().get(0).time()).isEqualTo("08:00");
		assertThat(first.slots().get(1).status()).isEqualTo(SlotStatus.RESERVED);

		Slot own = t.machines().get(1).slots().get(2);
		assertThat(own.time()).isEqualTo("12:00");
		assertThat(own.status()).isEqualTo(SlotStatus.OWN);
		assertThat(own.reservationId()).isEqualTo(13131585L);
	}

	@Test
	void parsesMyReservations() {
		assertThat(HoasParser.myReservations(doc)).singleElement().satisfies(r -> {
			assertThat(r.date()).isEqualTo(LocalDate.of(2026, 10, 3));
			assertThat(r.start()).isEqualTo("12:00");
			assertThat(r.end()).isEqualTo("13:00");
			assertThat(r.machine()).isEqualTo("Kuivausrumpu 2");
			assertThat(r.serviceName()).isEqualTo("Kuivausrummut, Esimerkkikatu 1, A-rappu");
			assertThat(r.serviceId()).isEqualTo(724);
			assertThat(r.kind()).isEqualTo("dryer");
		});
		LocalDate day = LocalDate.of(2026, 10, 3);
		assertThat(HoasParser.ownReservationId(doc, day, "12:00", "Kuivausrumpu 2")).isEqualTo(13131585L);
		assertThat(HoasParser.ownReservationId(doc, day, "13:00", "Kuivausrumpu 2")).isNull();
		assertThat(HoasParser.offersCancelLink(doc, 13131585L)).isTrue();
		assertThat(HoasParser.offersCancelLink(doc, 1L)).isFalse();
	}

	@Test
	void detectsReserveLinks() {
		LocalDate day = LocalDate.of(2026, 10, 3);
		assertThat(HoasParser.offersReserveLink(doc, 913, "08.00", day)).isTrue();
		assertThat(HoasParser.offersReserveLink(doc, 913, "09.00", day)).isFalse();
	}

	@Test
	void parsesAnnouncements() {
		assertThat(HoasParser.announcements(doc)).hasSize(2).first().satisfies(a -> {
			assertThat(a.id()).isEqualTo(120593);
			assertThat(a.unread()).isTrue();
		});
	}

}
