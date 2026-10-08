package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Prüft die Konventionen aller Issue-Enums (Codes, Kürzel, Platzhalter). Neue Enums hier ergänzen. */
class IssueConventionsTest {

	private static final Pattern CODE = Pattern.compile("[A-Z]{3}-\\d{3}");

	private static List<Issue[]> allEnums() {
		return List.<Issue[]>of(SeatIssue.values());
	}

	private static List<Issue> allIssues() {
		List<Issue> all = new ArrayList<>();
		allEnums().forEach(values -> all.addAll(List.of(values)));
		return all;
	}

	@Test
	void codesHaveFormatAndAreUniqueAcrossAllEnums() {
		List<String> codes = allIssues().stream().map(Issue::getCode).collect(Collectors.toList());

		codes.forEach(code -> assertTrue(CODE.matcher(code).matches(), "Ungültiger Code: " + code));
		assertEquals(codes.size(), codes.stream().distinct().count(), "Codes müssen eindeutig sein");
	}

	@Test
	void eachEnumUsesExactlyOnePrefix() {
		for (Issue[] values : allEnums()) {
			long prefixes = List.of(values).stream().map(issue -> issue.getCode().substring(0, 3)).distinct().count();
			assertEquals(1, prefixes, "Ein Enum darf nur ein Kürzel verwenden: " + values[0].getClass().getSimpleName());
		}
	}

	@Test
	void placeholdersAreConsecutiveStartingAtOne() {
		for (Issue issue : allIssues()) {
			List<Integer> numbers = List.copyOf(MessageTemplate.placeholders(issue.getTemplate()));
			for (int i = 0; i < numbers.size(); i++) {
				assertEquals(i + 1, numbers.get(i), "Platzhalter nicht lückenlos in " + issue.getCode());
			}
		}
	}

	@Test
	void formattingWithAllParametersLeavesNoPlaceholder() {
		for (Issue issue : allIssues()) {
			int count = MessageTemplate.placeholders(issue.getTemplate()).size();
			Object[] params = new Object[count];
			java.util.Arrays.fill(params, "x");

			String text = issue.toIssue(params).message();

			assertFalse(Pattern.compile("&[1-9]").matcher(text).find(), "Platzhalter übrig in " + issue.getCode() + ": " + text);
		}
	}
}
