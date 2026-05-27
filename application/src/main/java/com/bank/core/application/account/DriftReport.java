package com.bank.core.application.account;

/**
 * Represents the outcome of a single balance drift audit tick window execution.
 */
public record DriftReport(long floor, long ceiling, int inspected, int drifted) {
}
