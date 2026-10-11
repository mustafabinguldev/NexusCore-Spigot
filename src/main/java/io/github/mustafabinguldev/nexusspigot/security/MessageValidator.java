package io.github.mustafabinguldev.nexusspigot.security;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

public interface MessageValidator {

    ValidationResult validate(NexusJsonDataContainer message);
}
