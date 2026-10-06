package de.eltviller_carneval_verein.karten.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ergebnis einer Plausibilitätsprüfung: eine (ggf. leere) Liste von {@link ValidationIssue}s.
 * Validatoren werfen nicht, sondern liefern dieses Ergebnis - die Oberfläche entscheidet,
 * wie sie damit umgeht (Fußzeile, Rückfrage, ...).
 */
public final class ValidationResult {

	private static final Comparator<ValidationIssue> BY_SEVERITY = Comparator.comparing(ValidationIssue::severity);

	private final List<ValidationIssue> issues = new ArrayList<>();

	public static ValidationResult ok() {
		return new ValidationResult();
	}

	public static ValidationResult of(ValidationIssue issue) {
		ValidationResult result = new ValidationResult();
		result.add(issue);
		return result;
	}

	public ValidationResult add(ValidationIssue issue) {
		issues.add(issue);
		return this;
	}

	public ValidationResult addAll(ValidationResult other) {
		issues.addAll(other.issues);
		return this;
	}

	/** Alle Beanstandungen, wichtigste zuerst (bei gleichem Schweregrad in der Reihenfolge des Auftretens). */
	public List<ValidationIssue> getIssues() {
		List<ValidationIssue> sorted = new ArrayList<>(issues);
		sorted.sort(BY_SEVERITY);
		return Collections.unmodifiableList(sorted);
	}

	/** Die wichtigste Beanstandung, falls es eine gibt. */
	public Optional<ValidationIssue> mostSevere() {
		return getIssues().stream().findFirst();
	}

	/** Keine Fehler (Warnungen sind erlaubt). */
	public boolean isValid() {
		return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
	}

	public boolean isEmpty() {
		return issues.isEmpty();
	}
}
