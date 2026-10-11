package io.github.mustafabinguldev.nexusspigot.redis;

public record RedisSettings(String host, int port, String username, String password,
                            int database, int timeoutMs, boolean ssl) {

    public boolean hasUser() {
        return username != null && !username.isBlank();
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
