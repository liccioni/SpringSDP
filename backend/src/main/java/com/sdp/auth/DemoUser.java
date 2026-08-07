package com.sdp.auth;

/**
 * One entry in the config-based demo user list (app.auth.users in
 * application.yml). Not persisted - see ADR 0016.
 */
public record DemoUser(String username, String passwordHash) {
}
