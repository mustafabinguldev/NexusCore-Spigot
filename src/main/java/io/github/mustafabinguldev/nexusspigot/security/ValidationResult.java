package io.github.mustafabinguldev.nexusspigot.security;

public record ValidationResult(boolean valid, String reason) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult reject(String reason) {
        return new ValidationResult(false, reason);
    }
}
