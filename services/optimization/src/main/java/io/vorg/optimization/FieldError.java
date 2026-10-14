package io.vorg.optimization;

/**
 * One invalid input field, problem-detail style: {@code field} is the JSON path ({@code trips[3].current_dock}),
 * {@code message} says what is wrong. Same shape as the API's {@code ApiProblemException.FieldError}.
 */
public record FieldError(String field, String message) {}
