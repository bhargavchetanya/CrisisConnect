package com.crisisconnect;

/**
 * Plain main class (does NOT extend Application).
 * Needed when running from a fat-jar / IDE without JavaFX module flags.
 */
public final class Launcher {
    public static void main(String[] args) {
        CrisisConnectApp.main(args);
    }
}
