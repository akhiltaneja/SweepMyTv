package com.sweepmytv.launcher;

// From Free My TV (app/src/io/freemytv/Spring.java), MIT License,
// Copyright (c) 2026 Free My TV contributors. See THIRD_PARTY_NOTICES.md.

/**
 * Damped spring, like macOS animations: response (s) and damping (1 = no bounce). Unlike a ValueAnimator, changing
 * the target mid-flight keeps the current speed, so quick D-pad presses glide instead of restarting from a standstill.
 */
final class Spring {
    float value, velocity, target;
    private final float response, damping, eps;

    /** For scales and fractions (settles within 0.0008). */
    Spring(float response, float damping, float initial) { this(response, damping, initial, 0.0008f); }

    /** {@code eps}: close enough to stop, in the value's own unit (0.25 for pixels). */
    Spring(float response, float damping, float initial, float eps) {
        this.response = response;
        this.damping = damping;
        this.eps = eps;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances {@code dt} seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < eps && Math.abs(velocity) < eps * 5;
    }
}
