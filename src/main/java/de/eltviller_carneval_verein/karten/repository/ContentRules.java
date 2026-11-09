package de.eltviller_carneval_verein.karten.repository;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Requires;
import de.eltviller_carneval_verein.karten.store.ContentGate;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Die Regel dieser Programmversion, nach der eine Version inhaltlich zu neu ist (siehe {@link ContentGate}). Sie sperrt,
 * was ein Rückfall auf einen Standardwert falsch darstellen würde:
 * <ul>
 * <li>{@code requires} (in jeder Art von Einheit) nennt ein Merkmal, das dieses Programm nicht kennt, oder hat einen
 * anderen Typ als ein Array aus Texten ({@link Requires#satisfied}). Fehlend, {@code null} und {@code []} verlangen
 * nichts.</li>
 * <li>Bei einer Buchung ist {@code paymentStatus} vorhanden und weder {@code null} noch ein Text aus
 * {@link PaymentStatus#values()}. Eine Zahl zählt ebenfalls (Jackson läse sie sonst als Ordinalzahl), ein Rückfall auf
 * {@link PaymentStatus#NONE} zeigte eine bezahlte Buchung als unbezahlt.</li>
 * </ul>
 */
public final class ContentRules implements ContentGate {

	/** Die Regel, die die Anwendung einsetzt. */
	public static final ContentRules GATE = new ContentRules();

	/** Die Felder, die diese Regel streng behandelt (Art der Einheit und Feldname) und nicht auf einen Standardwert zurückfällt. */
	public static final Set<String> STRICT_FIELDS = Set.of("Booking.paymentStatus");

	private static final String PAYMENT_STATUS = "paymentStatus";

	/** Wird nur von {@link #GATE} benutzt. */
	private ContentRules() {
	}

	@Override
	public boolean isNewer(UnitKey unit, ObjectNode payload) {
		if (!Requires.satisfied(payload.get(Requires.KEY))) {
			return true;
		}
		return unit.type() == UnitType.BOOKING && unknownPaymentStatus(payload.get(PAYMENT_STATUS));
	}

	private static boolean unknownPaymentStatus(JsonNode value) {
		if (value == null || value.isNull()) {
			return false;
		}
		if (!value.isTextual()) {
			return true;
		}
		String name = value.asText();
		return Arrays.stream(PaymentStatus.values()).noneMatch(status -> status.name().equals(name));
	}

	/**
	 * Berechnet aus allem, wovon die Regel abhängt: der Fassung der Regel selbst ({@code gate-1}), den Namen von
	 * {@link PaymentStatus} und den bekannten Merkmalen ({@link Requires#KNOWN}). Ein neuer Zahlstatus ändert sie von
	 * selbst.
	 */
	@Override
	public String rulesId() {
		return "gate-1;payment=" + sortedJoin(Arrays.stream(PaymentStatus.values()).map(Enum::name).collect(Collectors.toSet()))
				+ ";requires=" + sortedJoin(Requires.KNOWN);
	}

	private static String sortedJoin(Set<String> names) {
		return String.join(",", new TreeSet<>(names));
	}
}
